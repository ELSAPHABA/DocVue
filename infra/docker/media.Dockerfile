FROM python:3.12-slim-trixie@sha256:191083cdf6c7bda8c76158983163ead5d5cf7b77cf3033e805bb0f5296f51b4b
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg=7:7.1.5-0+deb13u1 libseccomp2=2.6.0-2 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 dockvue && useradd --uid 10001 --gid 10001 --no-create-home dockvue \
    && ffmpeg -version | grep -F 'ffmpeg version 7.1.5' \
    && ffprobe -version | grep -F 'ffprobe version 7.1.5' \
    && ffmpeg -hide_banner -encoders | grep -w libx264 \
    && ffmpeg -hide_banner -encoders | grep -w aac
WORKDIR /app
COPY services/media-worker/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY services/media-worker/worker.py ./worker.py
COPY services/media-worker/sandbox.py ./sandbox.py
USER 10001:10001
ENTRYPOINT ["python","/app/worker.py"]
