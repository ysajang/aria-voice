# ARIA Voice

안드로이드 음성 호출 앱 - 빅스비 대체

## 설치

1. Android Studio에서 이 폴더를 Open
2. `download_models.bat` 실행하여 ONNX 모델 다운로드
3. Gradle Sync
4. 에뮬레이터 또는 실기기에서 Run

## ONNX 모델

`app/src/main/assets/` 에 3개 파일 필요:
- `melspectrogram.onnx` - 오디오 전처리
- `embedding_model.onnx` - 음성 임베딩
- `hey_jarvis_v0.1.onnx` - 웨이크워드 (임시: "Hey Jarvis")

## 설정

- ARIA 서버 URL: 에뮬레이터 `http://10.0.2.2:8100` / 실기기 `http://PC_IP:8100`
- API Key: ARIA 서버의 X-API-Key
- 웨이크워드 민감도: 0.01 ~ 1.0

## 플로우

1. 서비스 시작 → 백그라운드 대기
2. "Hey Jarvis" 감지 (또는 마이크 버튼 탭)
3. 음성 → 텍스트 (Android STT)
4. 텍스트 → ARIA API 호출
5. 응답 → TTS 읽기
6. HITL → 확인/거부 버튼

## 기술 스택

- Kotlin + Jetpack Compose
- OpenWakeWord (ONNX Runtime)
- Android SpeechRecognizer (STT)
- Android TTS
- OkHttp + Kotlinx Serialization
- Foreground Service
