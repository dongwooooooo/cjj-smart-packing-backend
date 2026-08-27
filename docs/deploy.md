# EC2 자동 배포

develop에 머지되면 GitHub Actions가 EC2 인스턴스의 백엔드를 새 코드로 다시 띄운다. 워크플로 파일은 `.github/workflows/deploy-ec2.yml` 하나다.

## 이미지 파이프라인 (2026-08-27~)

인스턴스는 더 이상 빌드하지 않는다. CI 가 이미지를 굽고, 배포는 그 태그를 받아 실행한다.

```
develop push → build-push: Gradle·이미지 빌드 → ECR cj-ai-backend:{커밋7자리}, :latest
             → deploy-ec2(build-push 성공 시 자동): SSM → 인스턴스가 pull + compose up
             → /actuator/health UP 확인. 실패하면 직전 이미지로 되돌린다
```

- **롤백**: deploy-ec2 를 수동 실행하고 `image_tag` 에 이전 커밋 7자리를 넣는다. 빌드가 없으니 수십 초
- **로컬 개발은 그대로**: `docker-compose.override.yml` 이 `build: .` 로 덮어써서 소스에서 굽는다. EC2 는 `.env` 의 `COMPOSE_FILE=docker-compose.yml` 로 override 를 빼므로 ECR 이미지를 쓴다
- **권한**: CI 는 OIDC 역할(`github-actions-logistics-dimension`)로 push, 인스턴스는 인스턴스 프로파일의 `ecr-pull-backend` 정책으로 pull. 저장된 자격 증명 없음
- 인스턴스가 이미지를 못 받으면(권한·태그 오타) 컨테이너는 이전 상태 그대로다


## 대상

| 항목 | 값 |
| --- | --- |
| 인스턴스 | `i-0c7dac45358b3fafc` (Ubuntu, ap-northeast-2, 13.124.19.3) |
| 배포 경로 | 인스턴스의 `~/backend` (= `/home/ubuntu/backend`) |
| 기동 명령 | `sudo docker compose up -d --build` (db + backend) |
| 헬스 | `http://127.0.0.1:8000/actuator/health` → `{"status":"UP"}` |

## 구조

SSH 키도 인바운드 포트도 쓰지 않는다. 러너가 GitHub OIDC로 AWS 역할을 위임받아 SSM Run Command를 호출하고, Ubuntu AMI에 이미 들어 있는 SSM 에이전트가 인스턴스에서 명령을 실행한다. 배포 담당자 PC에 키페어가 있을 필요가 없고, 보안 그룹에 22번을 열어 둘 필요도 없다.

같은 조직의 `cj-ai-sw/ai` 레포가 쓰는 패턴과 같지만 코드를 옮기는 방법이 다르다. `ai`는 추론 코드 8개 파일을 tar+base64로 묶어 SSM 명령 본문에 인라인으로 싣는다. Spring Boot 소스 트리는 그 방식으로 실을 수 있는 크기가 아니라서, 이 레포는 인스턴스가 GitHub에서 직접 받는다. 러너의 `GITHUB_TOKEN`을 명령에 실어 보내고 인스턴스가 그 토큰으로 clone/fetch 한다.

### 잡 단계

1. **AWS 위임** — `aws-actions/configure-aws-credentials@v6`가 OIDC로 `secrets.AWS_ROLE_ARN` 역할을 받는다.
2. **대상 확정** — 인스턴스 ID와 배포할 git ref를 정하고 형식을 검사한다. push 이벤트면 ref는 그 실행을 만든 커밋 SHA다. 수동 실행이면 입력값(기본 `develop`)이다.
3. **인스턴스 확인** — `ssm describe-instance-information`으로 대상이 `Online` 인지 본다. 인스턴스 정지, 인스턴스 프로파일 누락, 에이전트 미등록이 모두 여기서 같은 모습으로 잡힌다. 확인 없이 명령을 보내면 `InvalidInstanceId`만 돌아와 원인을 알 수 없다.
4. **인스턴스 스크립트 작성** — 인스턴스에서 돌 bash 스크립트를 파일로 쓰고 `bash -n`으로 문법을 검사한다.
5. **SSM 요청 조립** — 스크립트를 base64로 인코딩하고, 토큰·ref·저장소 이름을 `shlex.quote`로 감싸 명령 본문을 만든다. 2026-08-25 기준 요청 본문은 5,415바이트다.
6. **실행과 대기** — `ssm send-command`로 보내고 종료까지 폴링한다. 표준 출력·표준 에러를 잡 로그에 찍고, 상태가 `Success`가 아니면 잡을 실패시킨다.

### 인스턴스에서 하는 일

명령은 root로 실행되는데 작업 사본·compose 프로젝트·`.env`는 모두 ubuntu 소유다. git은 다른 사용자 소유 저장소를 건드리기를 거부하므로(dubious ownership), root는 스크립트와 환경변수 파일을 배치하기만 하고 실제 배포는 `sudo -H -u ubuntu`로 내려가 실행한다. `-H`가 있어야 `$HOME`이 `/home/ubuntu`를 가리킨다.

1. `~/backend`가 git 저장소가 아니면 — 지금 인스턴스가 이 상태다. rsync로 복사된 스냅샷이다 — `~/backend.bak.<타임스탬프>`로 옮기고 새로 clone 한다. 지우지 않는 이유는 인스턴스에서 손으로 고친 게 있다면 그게 유일한 사본이기 때문이다. clone 뒤 백업의 `.env`를 새 작업 사본으로 복사한다.
2. 이미 git 저장소면 fetch 하고 지정 ref로 `reset --hard` 한다. `.env`는 `.gitignore`에 있어 reset이 건드리지 않는다.
3. `sudo docker compose up -d --build`.
4. 헬스를 5초 간격으로 최대 5분 폴링한다. `{"status":"UP"}`이 나오면 성공. 시간을 넘기면 `docker compose ps`와 `docker compose logs backend --tail 50`을 찍고 잡을 실패시킨다.

ref는 `origin/<ref>` → `<ref>` → `git fetch origin <ref>` 순으로 해석한다. 브랜치 이름, 태그, 커밋 SHA가 모두 들어간다.

명령 본문은 POSIX sh로 쓴다. SSM 에이전트가 스크립트를 `/bin/sh`로 넘기고 Ubuntu의 `/bin/sh`는 dash라, `set -o pipefail`이나 `[[ ]]` 같은 bash 문법은 이 층위에서 쓸 수 없다. bash가 필요한 인스턴스 스크립트는 `bash`로 따로 실행한다.

`ssm wait command-executed`는 5초 간격 20회, 즉 100초까지만 기다리고 포기한다. 이미지 pull 이 그보다 오래 걸릴 수 있어 이 대기를 30분 상한의 루프 안에서 반복 호출한다.

### 기본값

| 항목 | 값 | 바꾸는 곳 |
| --- | --- | --- |
| 리전 | `ap-northeast-2` | `env.AWS_REGION` |
| 인스턴스 ID | `i-0c7dac45358b3fafc` | 저장소 변수 `EC2_INSTANCE_ID` (없으면 기본값) |
| SSM 실행 제한 시간 | 1800초 | `env.SSM_EXECUTION_TIMEOUT` |
| 잡 폴링 상한 | 1800초 | `env.POLL_TIMEOUT_SECONDS` |
| 헬스체크 상한 | 300초 | `env.HEALTH_TIMEOUT_SECONDS` |

## 사전 세팅

한 번만 하면 된다. `cj-ai-sw/ai`가 쓰는 역할을 그대로 재사용하므로 AWS에서 새로 만들 건 없고, 신뢰 정책에 이 저장소를 추가하는 것과 GitHub 시크릿 등록 두 가지다.

### 1. AWS — 신뢰 정책에 backend 저장소 추가

`ai` 레포용으로 만든 역할(`github-actions-logistics-dimension`)의 신뢰 정책 `sub` 목록에 backend 항목 두 줄을 추가한다.

```json
"StringLike": {
  "token.actions.githubusercontent.com:sub": [
    "repo:cj-ai-sw/ai:*",
    "repo:cj-ai-sw@316033991/ai@1340552961:*",
    "repo:cj-ai-sw/backend:*",
    "repo:cj-ai-sw@316033991/backend@1340552275:*"
  ]
}
```

이 조직의 토큰은 `sub` 클레임에 소유자 ID·저장소 ID가 붙은 형식(`repo:cj-ai-sw@316033991/backend@1340552275:ref:...`)으로 발급된다. 이름만 쓴 패턴은 매칭에 실패해 `Not authorized to perform sts:AssumeRoleWithWebIdentity`가 나므로 두 형식을 모두 넣는다. 숫자는 토큰의 `repository_owner_id`·`repository_id` 클레임 값이다.

`:*`는 이 저장소의 모든 브랜치·태그를 허용한다. develop 머지에서만 쓰게 좁히려면 `repo:cj-ai-sw/backend:ref:refs/heads/develop`으로 바꾼다. 그렇게 하면 다른 브랜치에서 수동 실행할 때 위임이 거부된다.

권한 정책은 손대지 않는다. 대상 인스턴스가 `ai`와 같은 `i-0c7dac45358b3fafc`라서 `ssm:SendCommand`의 리소스 ARN과 `AWS-RunShellScript` 문서 ARN이 이미 들어 있다. 인스턴스를 따로 쓰게 되면 그때 `SsmSendCommand` 문에 인스턴스 ARN을 추가한다.

인스턴스 프로파일(`AmazonSSMManagedInstanceCore`)도 이미 붙어 있다. 확인:

```bash
aws ssm describe-instance-information \
  --region ap-northeast-2 \
  --filters Key=InstanceIds,Values=i-0c7dac45358b3fafc \
  --query 'InstanceInformationList[0].[InstanceId,PingStatus,AgentVersion]' \
  --output text
```

`PingStatus`가 `Online`이면 준비된 상태다.

### 2. GitHub — 시크릿 등록

이 저장소의 Settings → Secrets and variables → Actions → Secrets.

| 이름 | 값 |
| --- | --- |
| `AWS_ROLE_ARN` | 위 역할의 ARN. 예: `arn:aws:iam::123456789012:role/github-actions-logistics-dimension` |

`GITHUB_TOKEN`은 등록하지 않는다. Actions가 실행마다 자동으로 발급한다.

Variables 탭의 `EC2_INSTANCE_ID`는 선택이다. 등록하지 않으면 워크플로의 기본값을 쓴다. 인스턴스를 교체할 때 워크플로 파일 대신 이 변수를 고친다.

### 3. 인스턴스 — 첫 실행 전 확인

`~/backend/.env`가 있는지 본다. 지금 인스턴스에는 있다. 첫 배포에서 이 파일은 백업 디렉터리를 거쳐 새 작업 사본으로 복사된다. 없으면 경고만 남기고 `docker-compose.yml`의 기본값(`app`/`app`)으로 뜨는데, 기존 DB 볼륨의 계정과 다르면 백엔드가 DB 접속에 실패한다.

## 수동 실행

Actions 탭 → 왼쪽에서 `deploy-ec2` → 오른쪽 **Run workflow**.

- `ref` 입력을 기본값 `develop`으로 두면 develop의 최신 커밋이 올라간다. 왼쪽에서 고른 브랜치와 무관하다 — 실행할 워크플로 파일만 그 브랜치에서 읽는다.
- 특정 브랜치·태그·커밋을 올리려면 `ref`에 그 값을 넣는다. 이전 커밋으로 되돌릴 때 쓰는 방법이다.
- develop이 아닌 ref를 올리려면 IAM 신뢰 정책의 `sub` 조건이 `repo:cj-ai-sw/backend:*`여야 한다.

인스턴스에 직접 접속해 코드를 고치지 않는다. 고쳐도 다음 배포의 `reset --hard`가 덮어쓰고, 무엇이 올라가 있는지 레포만 봐서는 알 수 없게 된다.

## 토큰 노출 범위

인스턴스가 GitHub에서 코드를 받으려면 자격증명이 필요하다. 이 워크플로는 러너가 실행마다 발급받는 `GITHUB_TOKEN`을 쓴다. 권한은 `contents: read` 하나뿐이고, 잡이 끝나는 순간 만료된다.

**SSM 명령 이력** — 토큰은 명령 본문에 평문으로 실린다. AWS는 Run Command 이력을 30일간 보관하고 지우는 API는 없으므로, 이 계정에서 `ssm:ListCommands` 권한을 가진 사람은 콘솔에서 값을 읽을 수 있다. 다만 읽는 시점에는 이미 만료된 문자열이다. 실제로 위험한 창은 잡이 도는 몇 분뿐이고, 그 사이 이력을 읽을 수 있는 사람은 이미 SSM으로 인스턴스에 명령을 보낼 수 있는 사람이다.

SSM에는 명령 파라미터를 가리는 기능이 없다. `AWS-RunShellScript`의 `commands`는 평범한 문자열 목록이고, 에이전트가 파라미터를 스크립트 파일로 만들어 실행하는 구조라 stdin으로 넣는 경로도 없다. base64로 감싸도 되돌리는 데 키가 필요 없어 노출 범위는 같다.

**인스턴스 안쪽** — 세 곳 모두 막아 뒀다.

| 남을 수 있는 곳 | 처리 |
| --- | --- |
| `ps aux` (명령행 인자) | 토큰을 인자로 넘기지 않는다. 0600 파일에 쓰고, ubuntu 셸이 읽자마자 그 파일을 지운 뒤 배포 스크립트를 실행한다 |
| `.git/config` | 원격 URL은 `https://x-access-token@github.com/...` 형태로 사용자 이름만 담는다. 비밀번호는 git이 `GIT_ASKPASS` 헬퍼에 물어보고, 헬퍼는 현재 프로세스의 환경변수에서만 읽는다 |
| 셸 이력 | 대화형 셸이 아니라 이력 파일 자체가 없다 |

SSM 에이전트가 만드는 스크립트 파일(`/var/lib/amazon/ssm/` 아래)에는 명령 본문이 그대로 남는다. 이 디렉터리는 root 전용이라 일반 사용자는 읽을 수 없다.

**대안** — 만료되지 않는 자격증명이 필요해지면(예: 인스턴스가 스스로 주기적으로 받아야 하는 구조) GitHub App 설치 토큰이나 배포 키를 SSM Parameter Store `SecureString`에 두고 인스턴스가 꺼내 쓰는 방식으로 바꾼다. 그러면 명령 이력에는 파라미터 이름만 남는다. 지금은 잡 수명과 함께 죽는 토큰이라 그 세팅을 하지 않는다.

## 실패 시 확인 지점

| 증상 | 원인 | 조치 |
| --- | --- | --- |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 신뢰 정책에 backend 저장소 `sub` 두 줄이 없음 | 사전 세팅 1번 수행 |
| `Could not load credentials from any providers` | `AWS_ROLE_ARN` 시크릿 미등록 또는 오타 | Settings → Secrets 확인 |
| `인스턴스 ... 가 SSM 관리 대상이 아닙니다 (PingStatus=None)` | 인스턴스 정지, 인스턴스 프로파일 미연결, 에이전트 미등록 | `describe-instance-information`으로 `Online` 확인. 계속 비어 있으면 `sudo snap restart amazon-ssm-agent` |
| `AccessDeniedException ... ssm:SendCommand` | 역할 정책에 인스턴스 ARN 또는 문서 ARN이 빠짐 | `SsmSendCommand` 문의 리소스 두 줄 확인 |
| `git ref '...' 형식이 올바르지 않습니다` | 수동 실행 입력값에 허용되지 않는 문자 | 영문·숫자로 시작하고 `. _ / -` 만 쓴다 |
| 인스턴스 출력에 `Authentication failed` | `GITHUB_TOKEN` 권한 부족 또는 저장소 이름 불일치 | 워크플로 `permissions`에 `contents: read`가 있는지 확인 |
| 인스턴스 출력에 `detected dubious ownership` | 배포가 ubuntu 아닌 사용자로 돌았음 | `sudo -H -u ubuntu` 줄이 그대로인지 확인 |
| `헬스체크가 300초 안에 UP 이 되지 않았습니다` | 백엔드 기동 실패 | 같은 출력에 붙는 `docker compose logs backend --tail 50`을 본다. Flyway 체크섬 오류·DB 접속 실패가 대부분 |
| DB 접속 실패 (`password authentication failed`) | `.env`의 계정과 기존 `pgdata` 볼륨의 계정이 다름 | 인스턴스에서 `~/backend/.env` 확인. 백업 디렉터리(`~/backend.bak.*`)에 원본이 있다 |
| `1800초 안에 끝나지 않았습니다` | pull 이 상한을 넘김 | 커맨드는 인스턴스에서 계속 돌고 있을 수 있다. 콘솔 Run Command에서 상태 확인 후 `env.POLL_TIMEOUT_SECONDS` 조정 |
| 잡은 성공인데 외부에서 응답이 없음 | 컨테이너는 떴으나 보안 그룹이 막음 | 인바운드 8000이 호출자 IP 대역에 열려 있는지 확인 |

인스턴스 출력은 API가 24,000자에서 자른다. 로그가 길어 뒷부분이 잘리면 인스턴스에서 `cd ~/backend && sudo docker compose logs backend --tail 200`을 직접 본다.

실행 요약(Summary)에 인스턴스 ID와 SSM 커맨드 ID가 남는다. 콘솔에서 같은 커맨드를 찾을 때 쓴다.

## 아직 안 한 것

- **PR 검사** — develop 대상 PR에서 빌드·테스트를 돌리는 워크플로가 없다. 빌드가 깨진 커밋이 develop 에 들어가면 build-push 잡에서 발견된다 — 인스턴스에는 이미지가 안 올라가므로 서버는 이전 이미지로 계속 돈다.
- **무중단 배포** — `compose up -d` 가 컨테이너를 갈아 끼우는 동안 짧게 끊긴다. 인스턴스 한 대에 컨테이너 한 벌이라 롤링 교체할 대상이 없다.
- **자동 롤백** — 배포가 실패하면 인스턴스는 새 코드를 받은 상태로 남는다. 되돌리려면 이전 커밋 SHA를 `ref`에 넣어 수동 실행한다.
- **백업 디렉터리 정리** — `~/backend.bak.*`는 자동으로 지워지지 않는다. git 저장소가 아닌 상태에서만 만들어지므로 정상 운영에서는 첫 배포 때 한 번 생긴다. 내용을 확인한 뒤 손으로 지운다.
- **다중 인스턴스** — 인스턴스 하나에만 보낸다. 늘리면 태그 기반 타깃(`--targets Key=tag:...`)과 순차 배포 비율(`--max-errors`, `--max-concurrency`)을 함께 정해야 한다.
