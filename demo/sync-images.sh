#!/usr/bin/env bash
# 시연 사진을 S3 로 올린다 (D-25).
#
# 사진은 저장소에 두지 않는다 — 바이너리가 이력에 쌓이고, 실물 촬영이 붙으면 어차피 쓰지 않는다.
# products.json 의 images 항목이 곧 S3 키이므로 로컬 디렉토리 구조를 그대로 올리면 된다.
#
#   STORAGE_BUCKET=<버킷> bash demo/sync-images.sh [로컬_이미지_디렉토리]
#
# 기본 디렉토리는 demo/data/images 다. 데이터셋에서 추린 사진을 그 아래
# {gtin}/cam1.jpg, cam2.jpg, cam3.jpg 로 두고 실행한다.
#
# 촬영본과 함께 상품 대표 사진(demo/data/master/{gtin}.jpg)도 올린다. 대표 사진은
# 스캔 1단에서 화면에 뜨는 사진이고, 촬영본은 추론이 쓴다 — 둘은 쓰임이 다르다.
# 대표 사진 업로드 절차가 저장소에 없어서 한 장이 S3 에 빠진 채 시연까지 간 적이 있다.
set -euo pipefail

# macOS 는 bash 를 실행할 때 DYLD_* 환경변수를 지운다(SIP). homebrew python 을 쓰는
# aws CLI 는 그러면 expat 을 못 찾고 죽으므로 여기서 다시 넣는다. 리눅스에서는 무해하다.
if [ -d /opt/homebrew/opt/expat/lib ]; then
  export DYLD_LIBRARY_PATH="/opt/homebrew/opt/expat/lib:${DYLD_LIBRARY_PATH:-}"
fi

BUCKET="${STORAGE_BUCKET:?STORAGE_BUCKET 을 지정하세요}"
SRC="${1:-demo/data/images}"
REGION="${STORAGE_AWS_REGION:-ap-northeast-2}"

if [ ! -d "$SRC" ]; then
  echo "이미지 디렉토리가 없습니다: $SRC" >&2
  exit 1
fi

# 받아오다 끊긴 파일을 거른다. 앞 두 바이트가 JPEG 표식(0xFF 0xD8)인지, 크기가
# 사진이라 할 만한지 본다 — 3 바이트짜리 잘린 파일이 저장소에 들어간 적이 있다.
check_jpeg() {
  local f="$1"
  local size
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
MASTER_SRC="$(dirname "$SRC")/master"
if [ -d "$MASTER_SRC" ]; then
  while IFS= read -r photo; do
    check_jpeg "$photo" || missing=1
  done < <(find "$MASTER_SRC" -maxdepth 1 -name '*.jpg')
fi

[ "$missing" -eq 0 ] || exit 1

aws s3 sync "$SRC" "s3://${BUCKET}/images/" \
  --region "$REGION" \
  --content-type image/jpeg \
  --exclude '*' --include '*.jpg' \
  --delete

echo "완료: s3://${BUCKET}/images/ ($(find "$SRC" -name '*.jpg' | wc -l | tr -d ' ') 장)"

# 대표 사진. --delete 로 저장소를 정답으로 삼는다 — 목록에서 뺀 상품 사진이 S3 에
# 남아 있으면 지운 상품이 화면에 계속 뜬다.
if [ -d "$MASTER_SRC" ]; then
  aws s3 sync "$MASTER_SRC" "s3://${BUCKET}/master/" \
    --region "$REGION" \
    --content-type image/jpeg \
    --exclude '*' --include '*.jpg' \
    --delete
  echo "완료: s3://${BUCKET}/master/ ($(find "$MASTER_SRC" -name '*.jpg' | wc -l | tr -d ' ') 장)"
fi
