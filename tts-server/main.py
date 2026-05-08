"""
ARIA Voice TTS Server — MeloTTS Korean
독립 마이크로서비스: POST /v1/tts → WAV audio
"""

import io
import os
import time
import logging
import tempfile
from contextlib import asynccontextmanager
from typing import Optional

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel, Field

load_dotenv()

# ──────────────────────────────────────────────
# Config
# ──────────────────────────────────────────────
API_KEY = os.getenv("TTS_API_KEY", "")
HOST = os.getenv("TTS_HOST", "0.0.0.0")
PORT = int(os.getenv("TTS_PORT", "8200"))
LOG_LEVEL = os.getenv("TTS_LOG_LEVEL", "INFO")

logging.basicConfig(
    level=getattr(logging, LOG_LEVEL),
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("aria-tts")

# ──────────────────────────────────────────────
# Emotion → speed mapping
# MeloTTS는 voice 톤 자체는 고정이지만
# speed 파라미터로 감정 뉘앙스 차이를 줌
# ──────────────────────────────────────────────
EMOTION_SPEED_MAP: dict[str, float] = {
    "happy": 1.15,      # 밝고 빠르게
    "excited": 1.20,     # 신나게
    "calm": 0.85,        # 차분하게
    "sad": 0.80,         # 느리고 가라앉게
    "serious": 0.90,     # 진지하게
    "neutral": 1.0,      # 기본
    "comforting": 0.88,  # 위로하듯
    "urgent": 1.25,      # 급한
}

# ──────────────────────────────────────────────
# Model loading (lifespan)
# ──────────────────────────────────────────────
tts_model = None
speaker_id = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """서버 시작 시 MeloTTS 모델 로드 / 종료 시 정리"""
    global tts_model, speaker_id

    logger.info("Loading MeloTTS Korean model...")
    start = time.time()

    try:
        from melo.api import TTS
        tts_model = TTS(language="KR", device="cpu")
        speaker_id = tts_model.hps.data.spk2id["KR"]
        elapsed = time.time() - start
        logger.info(f"MeloTTS Korean loaded in {elapsed:.1f}s (speaker_id={speaker_id})")
    except Exception as e:
        logger.error(f"Failed to load MeloTTS: {e}")
        raise

    yield

    logger.info("TTS server shutting down")
    tts_model = None


# ──────────────────────────────────────────────
# FastAPI app
# ──────────────────────────────────────────────
app = FastAPI(
    title="ARIA Voice TTS",
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["POST", "GET"],
    allow_headers=["*"],
)

# ──────────────────────────────────────────────
# Rate limiting (simple in-memory)
# ──────────────────────────────────────────────
_rate_limit: dict[str, list[float]] = {}
RATE_LIMIT_RPM = int(os.getenv("TTS_RATE_LIMIT_RPM", "30"))


def check_rate_limit(client_ip: str) -> bool:
    now = time.time()
    window = 60.0
    timestamps = _rate_limit.get(client_ip, [])
    timestamps = [t for t in timestamps if now - t < window]
    if len(timestamps) >= RATE_LIMIT_RPM:
        return False
    timestamps.append(now)
    _rate_limit[client_ip] = timestamps
    return True


# ──────────────────────────────────────────────
# Auth
# ──────────────────────────────────────────────
def verify_api_key(x_api_key: Optional[str]) -> bool:
    if not API_KEY:
        return True  # API key 미설정이면 인증 스킵
    return x_api_key == API_KEY


# ──────────────────────────────────────────────
# Request / Response models
# ──────────────────────────────────────────────
class TtsRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=2000, description="합성할 텍스트")
    emotion: str = Field("neutral", description="감정 키워드")
    speed: Optional[float] = Field(None, ge=0.5, le=2.0, description="직접 지정 시 emotion 무시")


# ──────────────────────────────────────────────
# Endpoints
# ──────────────────────────────────────────────
@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "model_loaded": tts_model is not None,
        "emotions": list(EMOTION_SPEED_MAP.keys()),
    }


@app.post("/v1/tts")
def synthesize(
    req: TtsRequest,
    request: Request,
    x_api_key: Optional[str] = Header(None),
):
    # Auth
    if not verify_api_key(x_api_key):
        raise HTTPException(status_code=401, detail="Invalid API key")

    # Rate limit
    client_ip = request.client.host if request.client else "unknown"
    if not check_rate_limit(client_ip):
        raise HTTPException(status_code=429, detail="Rate limit exceeded")

    # Model check
    if tts_model is None or speaker_id is None:
        raise HTTPException(status_code=503, detail="TTS model not loaded")

    # Sanitize text
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Empty text")

    # Determine speed
    if req.speed is not None:
        speed = req.speed
    else:
        speed = EMOTION_SPEED_MAP.get(req.emotion, 1.0)

    # Synthesize to temp file → read bytes
    start = time.time()
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=True) as tmp:
            tts_model.tts_to_file(
                text,
                speaker_id,
                tmp.name,
                speed=speed,
            )
            tmp.seek(0)
            audio_bytes = tmp.read()
    except Exception as e:
        logger.error(f"TTS synthesis failed: {e}")
        raise HTTPException(status_code=500, detail="Synthesis failed")

    elapsed = time.time() - start
    logger.info(
        f"TTS OK: {len(text)} chars / emotion={req.emotion} / "
        f"speed={speed:.2f} / {elapsed:.2f}s / {len(audio_bytes)} bytes"
    )

    return Response(
        content=audio_bytes,
        media_type="audio/wav",
        headers={
            "X-TTS-Duration-Ms": str(int(elapsed * 1000)),
            "X-TTS-Emotion": req.emotion,
            "X-TTS-Speed": f"{speed:.2f}",
        },
    )


# ──────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=HOST,
        port=PORT,
        log_level=LOG_LEVEL.lower(),
    )
