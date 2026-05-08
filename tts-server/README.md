# ARIA Voice TTS Server

MeloTTS Korean 기반 음성 합성 마이크로서비스.

## 요구사항

- Python 3.10+
- CPU (GPU 불필요)
- RAM 2GB+ 권장

## 설치 (Hetzner 서버)

```bash
cd /opt
git clone https://github.com/ysajang/aria-voice.git
cd aria-voice/tts-server

python3 -m venv venv
source venv/bin/activate

pip install -r requirements.txt
```

## 설정

```bash
cp .env.example .env
nano .env
# TTS_API_KEY 설정 필수
```

## 실행

```bash
# 개발
source venv/bin/activate
python main.py

# systemd (프로덕션)
sudo cp aria-tts.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aria-tts
```

## API

### POST /v1/tts

```bash
curl -X POST http://localhost:8200/v1/tts \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-key-here" \
  -d '{"text": "안녕하세요 아리아입니다", "emotion": "happy"}' \
  --output test.wav
```

**Request Body:**
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| text | string | O | 합성할 텍스트 (1-2000자) |
| emotion | string | X | 감정 키워드 (기본: neutral) |
| speed | float | X | 직접 속도 지정 (0.5-2.0 / emotion 무시) |

**감정 키워드:**
| emotion | speed | 설명 |
|---------|-------|------|
| happy | 1.15 | 밝고 빠르게 |
| excited | 1.20 | 신나게 |
| calm | 0.85 | 차분하게 |
| sad | 0.80 | 느리고 가라앉게 |
| serious | 0.90 | 진지하게 |
| neutral | 1.00 | 기본 |
| comforting | 0.88 | 위로하듯 |
| urgent | 1.25 | 급한 |

**Response:** `audio/wav` binary

### GET /health

```json
{"status": "ok", "model_loaded": true, "emotions": ["happy", ...]}
```
