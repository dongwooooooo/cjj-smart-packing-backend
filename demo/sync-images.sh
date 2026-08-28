#!/usr/bin/env bash
# 시연 사진을 S3 로 올린다 (D-25).
#
# 사진은 두 종류다. 쓰임이 다르고 올라가는 자리도 다르다.
#
#   촬영본      demo/data/images/{gtin}/cam1~3.jpg  ->  s3://버킷/images/
#               치수 추론이 읽는다. 저장소에 두지 않는다(바이너리가 이력에 쌓인다).
#   대표 사진   demo/data/master/{gtin}.jpg         ->  s3://버킷/master/
#               스캔 1단에서 화면에 뜬다. 저장소에 둔다.
#
#   STORAGE_BUCKET=<버킷> bash demo/sync-images.sh [촬영본_디렉토리]
#
# 촬영본은 저장소에 없으므로 평소 실행에서는 대표 사진만 올라간다. 촬영본까지 올리려면
# 데이터셋에서 추린 사진을 demo/data/images/{gtin}/cam1~3.jpg 로 두고 실행한다.
#
# 대표 사진 업로드 절차가 없어서 한 장이 S3 에 빠진 채 시연까지 간 적이 있다.
set -euo pipefail

# macOS 는 bash 를 실행할 때 DYLD_* 환경변수를 지운다(SIP). homebrew python 을 쓰는
# aws CLI 는 그러면 expat 을 못 찾고 죽으므로 여기서 다시 넣는다. 리눅스에서는 무해하다.
if [ -d /opt/homebrew/opt/expat/lib ]; then
  export DYLD_LIBRARY_PATH="/opt/homebrew/opt/expat/lib:${DYLD_LIBRARY_PATH:-}"
fi

BUCKET="${STORAGE_BUCKET:?STORAGE_BUCKET 을 지정하세요}"
SRC="${1:-demo/data/images}"
MASTER_SRC="${MASTER_DIR:-$(dirname "$SRC")/master}"
REGION="${STORAGE_AWS_REGION:-ap-northeast-2}"

# 받아오다 끊긴 파일을 거른다. 앞 두 바이트가 JPEG 표식(0xFF 0xD8)인지, 크기가 사진이라
# 할 만한지 본다 — 3 바이트짜리 잘린 파일이 저장소에 들어가 병합된 적이 있다.
check_jpeg() {
  local f="$1" size
  size=$(wc -c < "$f" | tr -d ' ')
  if [ "$size" -lt 1024 ]; then
    echo "사진이 온전하지 않습니다(${size} 바이트): $f" >&2
    return 1
  fi
  if [ "$(od -An -tx1 -N2 "$f" | tr -d ' \n')" != "ffd8" ]; then
    echo "JPEG 가 아닙니다: $f" >&2
    return 1
  fi
}

count_jpegs() {
  [ -d "$1" ] || { echo 0; return; }
  find "$1" -name '*.jpg' | wc -l | tr -d ' '
}

# 올릴 게 하나도 없는 디렉토리로 --delete 를 돌리면 S3 쪽이 통째로 지워진다. 촬영본이
# 지워지면 추론이 읽을 사진이 없어 시연이 멈춘다. 그래서 각 묶음은 올릴 파일이 실제로
# 있을 때만 돌리고, 없으면 S3 를 건드리지 않고 넘어간다.
uploaded=0

if [ "$(count_jpegs "$SRC")" -gt 0 ]; then
  # 상품마다 카메라 3대분이 다 있어야 추론이 돈다. 빠진 게 있으면 올리기 전에 멈춘다.
  missing=0
  while IFS= read -r dir; do
    for cam in 1 2 3; do
      if [ -f "$dir/cam$cam.jpg" ]; then
        check_jpeg "$dir/cam$cam.jpg" || missing=1
      else
        echo "빠진 파일: $dir/cam$cam.jpg" >&2
        missing=1
      fi
    done
  done < <(find "$SRC" -mindepth 1 -maxdepth 1 -type d)
  [ "$missing" -eq 0 ] || exit 1

  aws s3 sync "$SRC" "s3://${BUCKET}/images/" \
    --region "$REGION" \
    --content-type image/jpeg \
    --exclude '*' --include '*.jpg' \
    --delete
  echo "완료: s3://${BUCKET}/images/ ($(count_jpegs "$SRC") 장)"
  uploaded=1
else
  echo "촬영본을 건너뜁니다 — $SRC 에 사진이 없습니다. S3 의 촬영본은 그대로 둡니다."
fi

if [ "$(count_jpegs "$MASTER_SRC")" -gt 0 ]; then
  missing=0
  while IFS= read -r photo; do
    check_jpeg "$photo" || missing=1
  done < <(find "$MASTER_SRC" -maxdepth 1 -name '*.jpg')
  [ "$missing" -eq 0 ] || exit 1

  # --delete 로 저장소를 정답으로 삼는다. 시연 목록에서 뺀 상품의 사진을 지우려면
  # demo/data/master/ 에서 그 파일을 지운 뒤 돌려야 한다. 목록(products.json)만
  # 줄이는 것으로는 S3 에서 사라지지 않는다.
  aws s3 sync "$MASTER_SRC" "s3://${BUCKET}/master/" \
    --region "$REGION" \
    --content-type image/jpeg \
    --exclude '*' --include '*.jpg' \
    --delete
  echo "완료: s3://${BUCKET}/master/ ($(count_jpegs "$MASTER_SRC") 장)"
  uploaded=1
else
  echo "대표 사진을 건너뜁니다 — $MASTER_SRC 에 사진이 없습니다. S3 의 대표 사진은 그대로 둡니다."
fi

[ "$uploaded" -eq 1 ] || { echo "올릴 사진이 없습니다." >&2; exit 1; }
