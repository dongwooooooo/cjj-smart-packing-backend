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

# 상품마다 카메라 3대분이 다 있어야 추론이 돈다. 빠진 게 있으면 올리기 전에 멈춘다.
missing=0
while IFS= read -r dir; do
  for cam in 1 2 3; do
    [ -f "$dir/cam$cam.jpg" ] || { echo "빠진 파일: $dir/cam$cam.jpg" >&2; missing=1; }
  done
done < <(find "$SRC" -mindepth 1 -maxdepth 1 -type d)
[ "$missing" -eq 0 ] || exit 1

aws s3 sync "$SRC" "s3://${BUCKET}/images/" \
  --region "$REGION" \
  --content-type image/jpeg \
  --exclude '*' --include '*.jpg' \
  --delete

echo "완료: s3://${BUCKET}/images/ ($(find "$SRC" -name '*.jpg' | wc -l | tr -d ' ') 장)"
