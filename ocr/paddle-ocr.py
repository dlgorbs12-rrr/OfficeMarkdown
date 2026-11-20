"""Offline Korean and English OCR for an OfficeMarkdown image worker."""

import base64
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory


DETECTION_MODEL = "PP-OCRv5_mobile_det"
RECOGNITION_MODEL = "korean_PP-OCRv5_mobile_rec"


class WorkerError(Exception):
    pass


def require_local_path(value):
    path = Path(value)
    if not path.is_absolute() or path.drive.startswith("\\\\"):
        raise ValueError("Expected an absolute local path")
    return path


def model_directory(root, name):
    path = root / (name + "_infer")
    files = ("inference.json", "inference.pdiparams", "inference.yml")
    if not all((path / filename).is_file() for filename in files):
        raise WorkerError("로컬 PaddleOCR 모델 파일이 필요합니다. install-paddle.ps1을 실행하세요.")
    return str(path)


def offline(event, _args):
    if event in ("socket.connect", "socket.getaddrinfo", "socket.gethostbyname", "socket.sendto"):
        raise RuntimeError("PaddleOCR requires offline model files")


def create_pipeline():
    try:
        root = require_local_path(os.environ.get("OFFICEMD_PADDLE_MODELS", ""))
    except ValueError as exc:
        raise WorkerError("OFFICEMD_PADDLE_MODELS에 로컬 모델 폴더의 절대 경로를 설정하세요.") from exc
    detection = model_directory(root, DETECTION_MODEL)
    recognition = model_directory(root, RECOGNITION_MODEL)
    try:
        from paddleocr import PaddleOCR
    except ImportError as exc:
        raise WorkerError("PaddleOCR 실행 환경이 필요합니다. install-paddle.ps1을 실행하세요.") from exc

    try:
        return PaddleOCR(
            text_detection_model_name=DETECTION_MODEL,
            text_detection_model_dir=detection,
            text_recognition_model_name=RECOGNITION_MODEL,
            text_recognition_model_dir=recognition,
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            device="cpu",
            cpu_threads=4,
            enable_mkldnn=True,
        )
    except Exception as exc:
        raise WorkerError("PaddleOCR 모델을 시작할 수 없습니다. 로컬 설치를 확인하세요.") from exc


def serve(protocol):
    pipeline = None
    for request in sys.stdin.buffer:
        try:
            try:
                path = require_local_path(base64.b64decode(request.strip(), validate=True).decode("utf-8"))
                if not path.is_file():
                    raise ValueError("Missing input")
            except (ValueError, OSError) as exc:
                raise WorkerError("OCR 입력 이미지 파일을 읽을 수 없습니다.") from exc
            if pipeline is None:
                pipeline = create_pipeline()
            results = pipeline.predict(str(path))
            text = "\n".join(value for result in results for value in result["rec_texts"])
            status = b"OK "
        except WorkerError as exc:
            text, status = str(exc), b"ERR "
        except Exception:
            text, status = "PaddleOCR 이미지 인식에 실패했습니다.", b"ERR "
        protocol.write(status + base64.b64encode(text.encode("utf-8")) + b"\n")
        protocol.flush()


def main():
    if sys.argv[1:] not in (["--worker"], ["-Worker"]):
        return 2

    sys.addaudithook(offline)
    os.environ["PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK"] = "True"
    os.environ["OMP_THREAD_LIMIT"] = "4"
    with TemporaryDirectory(prefix="office-paddle-") as cache:
        os.environ["PADDLE_PDX_CACHE_HOME"] = cache
        # Keep native library logs and paths out of the parent process protocol and diagnostics.
        with os.fdopen(os.dup(sys.stdout.fileno()), "wb") as protocol, open(os.devnull, "wb") as silent:
            os.dup2(silent.fileno(), sys.stdout.fileno())
            os.dup2(silent.fileno(), sys.stderr.fileno())
            serve(protocol)
    return 0


if __name__ == "__main__":
    sys.exit(main())
