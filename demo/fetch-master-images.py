"""코리안넷 공개 GTIN 조회 페이지에서 시연 상품의 대표 사진을 받는다.

사용: python3 demo/fetch-master-images.py [gtin ...]
인자가 없으면 demo/data/products.json 의 상품 전부. 결과는 demo/data/master/{gtin}.jpg.
이미지가 등록되지 않은 상품(fileNm 이 빈 값)은 건너뛰고 목록에 남긴다.
시연 중에는 코리안넷을 부르지 않는다. 미리 받아 저장소와 S3(master/)에 둔다.
"""
import json, re, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SEARCH = "https://www.koreannet.or.kr/front/koreannet/gtinSrch.do?gtin={gtin}"
PHOTO = "https://gs1.koreannet.or.kr/pr/photoView.do?fileNm={file_nm}&filePath={file_path}"
UA = {"User-Agent": "Mozilla/5.0 (cjj-demo master photo fetch)"}
LINK = re.compile(r"photoView\.do\?fileNm=([^&\"']*)&(?:amp;)?filePath=([^\"'\s)]+)")

def fetch(url: str, tries: int = 3) -> bytes:
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read()
        except (TimeoutError, OSError):
            if i == tries - 1:
                raise
            time.sleep(3)
    raise RuntimeError("unreachable")

def main() -> int:
    gtins = sys.argv[1:] or [p["gtin"] for p in json.loads((ROOT / "data/products.json").read_text())]
    out_dir = ROOT / "data/master"; out_dir.mkdir(exist_ok=True)
    missing = []
    for gtin in gtins:
        m = LINK.search(fetch(SEARCH.format(gtin=gtin)).decode("utf-8", "ignore"))
        if not m or not m.group(1):
            missing.append(gtin); print(f"{gtin}: 이미지 없음"); continue
        img = fetch(PHOTO.format(file_nm=m.group(1), file_path=m.group(2)))
        if len(img) < 1024 or img[:2] != b"\xff\xd8":
            missing.append(gtin); print(f"{gtin}: 응답이 JPEG 가 아님 ({len(img)}B)"); continue
        (out_dir / f"{gtin}.jpg").write_bytes(img)
        print(f"{gtin}: {len(img)//1024}KB")
        time.sleep(1)
    if missing:
        print("사진 없는 상품:", ", ".join(missing))
    return 0

if __name__ == "__main__":
    sys.exit(main())
