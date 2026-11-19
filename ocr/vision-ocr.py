"""Use the pinned Microsoft MarkItDown OCR service on one embedded image."""
import base64
import os
import sys

from markitdown_ocr._ocr_service import LLMVisionOCRService
from openai import OpenAI

def recognize(service, path):
    with open(path, "rb") as image:
        result = service.extract_text(image)
    if result.error:
        raise RuntimeError("Vision OCR request failed. Check endpoint, model, credentials and quota.")
    return result.text


def main():
    client = OpenAI(timeout=45.0, max_retries=0)
    service = LLMVisionOCRService(client, os.environ["OFFICEMD_VISION_MODEL"])
    if sys.argv[1] != "--worker":
        sys.stdout.buffer.write(recognize(service, sys.argv[1]).encode("utf-8"))
        return 0
    for request in sys.stdin.buffer:
        try:
            path = base64.b64decode(request.strip()).decode("utf-8")
            text, status = recognize(service, path), b"OK "
        except Exception as exc:
            text, status = str(exc), b"ERR "
        sys.stdout.buffer.write(status + base64.b64encode(text.encode("utf-8")) + b"\n")
        sys.stdout.buffer.flush()
    return 0

if __name__ == "__main__":
    sys.exit(main())

