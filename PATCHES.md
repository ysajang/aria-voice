# Speaker Verification — Code Patch Instructions

## 1. app/build.gradle.kts  →  의존성 추가

```kotlin
// ONNX Runtime for Android
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
// Encrypted SharedPreferences
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

---

## 2. WakeWordEngine.kt  →  CircularAudioBuffer 연결

### 2-a. import 추가 (파일 상단)
```kotlin
import com.ysajang.ariavoice.speaker.CircularAudioBuffer
```

### 2-b. 클래스 프로퍼티 추가 (companion object 또는 클래스 바디 상단)
```kotlin
// 2.5초 @ 16kHz = 40,000 samples
private val audioBuffer = CircularAudioBuffer(capacitySamples = 40_000)
```

### 2-c. AudioRecorder 콜백 안에서 write 호출
AudioRecorder가 FloatArray 청크를 전달할 때마다 버퍼에 기록.
기존 코드에서 `onAudioSamples(floatArray)` 또는 `processAudio(chunk)` 등 청크를 받는 지점에 아래 한 줄 추가:
```kotlin
audioBuffer.write(chunk)   // chunk = FloatArray (raw short→float)
```

### 2-d. 웨이크워드 트리거 시 오디오 노출
WakeWord 감지 콜백 (예: `onWakeWordDetected()`) 내부에서
리스너에게 오디오 버퍼를 함께 전달하도록 인터페이스/콜백 수정:

```kotlin
// 기존
interface WakeWordListener {
    fun onWakeWordDetected()
}

// 변경 후
interface WakeWordListener {
    fun onWakeWordDetected(audioSnapshot: FloatArray)
}

// 트리거 지점에서:
listener?.onWakeWordDetected(
    audioSnapshot = audioBuffer.readLast(24_000)   // 최근 1.5초
)
```

---

## 3. AriaForegroundService.kt  →  검증 단계 삽입

### 3-a. import 추가
```kotlin
import com.ysajang.ariavoice.speaker.SpeakerVerifier
import com.ysajang.ariavoice.speaker.VerifyResult
```

### 3-b. 프로퍼티 추가 (Service 클래스 바디)
```kotlin
private val speakerVerifier by lazy { SpeakerVerifier(this) }
```

### 3-c. onCreate() 에 초기화
```kotlin
override fun onCreate() {
    super.onCreate()
    // ... 기존 코드 ...
    speakerVerifier.initialize()          // ONNX 세션 로드 (I/O 스레드)
}
```

### 3-d. WakeWordListener 구현부 수정

```kotlin
// 기존
override fun onWakeWordDetected() {
    startSttFlow()
}

// 변경 후
override fun onWakeWordDetected(audioSnapshot: FloatArray) {
    val result: VerifyResult = speakerVerifier.verify(audioSnapshot)
    if (!result.accepted) {
        // 로그만 남기고 무시 (이미 SpeakerVerifier 내부에서 speaker_rejected 로그됨)
        return
    }
    startSttFlow()
}
```

### 3-e. onDestroy() 에 해제
```kotlin
override fun onDestroy() {
    speakerVerifier.release()
    super.onDestroy()
}
```

---

## 4. Settings Navigation  →  등록 화면 진입

Navigation graph (nav_graph.xml 또는 NavHost) 에 enrollment destination 추가:

```kotlin
// Compose NavHost 예시
composable("speaker_enrollment") {
    val vm = remember {
        SpeakerEnrollmentViewModel(
            context  = context,
            verifier = speakerVerifier,   // Service 또는 shared instance
        )
    }
    SpeakerEnrollmentScreen(
        viewModel           = vm,
        onEnrollmentComplete = { navController.popBackStack() },
        onBack              = { navController.popBackStack() },
    )
}
```

Settings 화면 내 "화자 등록" ListItem 에 `navController.navigate("speaker_enrollment")` 연결.

---

## 5. ONNX 모델 배포

WSL에서 export 완료 후:

```bash
# WSL → Windows 복사
cp /home/seungjae/projects(wsl)/aria-engine/speaker-verification/models/ecapa_speaker.onnx \
   /mnt/c/Users/Administrator/project\(win\)/aria-voice/app/src/main/assets/ecapa_speaker.onnx
```

assets/ 폴더가 없으면:
```
app/src/main/assets/      ← 여기에 ecapa_speaker.onnx 배치
```

---

## 6. 권한 (AndroidManifest.xml)

enrollment 화면에서 마이크 사용 → 이미 RECORD_AUDIO 권한 있으면 추가 불필요.
없으면:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```
