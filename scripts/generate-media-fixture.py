"""Create a reproducible synthetic fixture in the isolated media worker (no source media)."""
import subprocess, struct, sys
from pathlib import Path

def main():
    path=Path(sys.argv[1])
    subprocess.run(["ffmpeg","-y","-hide_banner","-loglevel","error",
        "-f","lavfi","-i","testsrc2=size=1280x720:rate=30",
        "-f","lavfi","-i","sine=frequency=440:sample_rate=48000",
        "-t","10","-c:v","libx264","-threads","2","-preset","fast",
        "-pix_fmt","yuv420p","-c:a","aac","-movflags","+faststart",str(path)],check=True)
    # A legal trailing MP4 'free' atom forces two multipart chunks without changing the video.
    target=17*1024*1024
    extra=target-path.stat().st_size
    if extra>=8:
        with path.open("ab") as f:
            f.write(struct.pack(">I4s",extra,b"free"))
            f.truncate(target)
    print("fixture size:",path.stat().st_size)
if __name__=="__main__":main()
