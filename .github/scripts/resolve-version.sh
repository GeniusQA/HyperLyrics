#!/usr/bin/env bash
set -euo pipefail

version_file="${VERSION_FILE:-app/build.gradle.kts}"

source_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=.*"([^"]+)".*/\1/p' "$version_file" | head -n1)"
source_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=*[[:space:]]*([0-9]+).*/\1/p' "$version_file" | head -n1)"

if [[ -z "$source_name" ]]; then
  echo "Unable to read versionName from $version_file" >&2
  exit 1
fi
if [[ ! "$source_code" =~ ^[1-9][0-9]*$ ]]; then
  echo "versionCode must be a positive integer: $source_code" >&2
  exit 1
fi

# 从 X.X.X / X.X.X-beta / X.X.X-canary 解析 base 版本与渠道后缀
if [[ "$source_name" =~ ^([0-9]+\.[0-9]+\.[0-9]+)(-(beta|canary))?$ ]]; then
  base_version="${BASH_REMATCH[1]}"
  parsed_channel="${BASH_REMATCH[3]:-stable}"
else
  echo "versionName must use X.X.X, X.X.X-beta, or X.X.X-canary: $source_name" >&2
  exit 1
fi

# 渠道优先取外部传入的 CHANNEL（workflow_dispatch 输入），否则按 versionName 后缀推断
channel="${CHANNEL:-$parsed_channel}"
case "$channel" in
  canary|beta|stable) ;;
  *)
    echo "Unknown channel: $channel (expected canary/beta/stable)" >&2
    exit 1
    ;;
esac

current_commit="$(git rev-parse HEAD)"

if [[ "$channel" == "canary" ]]; then
  # canary 始终固定为 base_version（例如 1.0.0），不自动递增、不加后缀
  full_version="$base_version"
else
  # stable / beta：以 base_version 为起点，按已有正式版 tag 自动递增 patch
  major_minor="${base_version%.*}"
  mm_regex="${major_minor//./\\.}"
  highest_patch=-1

  while IFS= read -r tag; do
    # 仅统计正式版 tag（vX.Y.Z）占号，beta 预发布不占号
    if [[ "$tag" =~ ^v${mm_regex}\.([0-9]+)$ ]]; then
      number="${BASH_REMATCH[1]}"
      if (( number > highest_patch )); then
        highest_patch="$number"
      fi
    fi
  done < <(git tag --list "v${major_minor}.*")

  if (( highest_patch < 0 )); then
    next_version="$base_version"
  else
    next_version="${major_minor}.$(( highest_patch + 1 ))"
  fi

  if [[ "$channel" == "beta" ]]; then
    candidate="${next_version}-beta"
    if git rev-parse -q --verify "refs/tags/v${candidate}" >/dev/null 2>&1; then
      n=1
      while git rev-parse -q --verify "refs/tags/v${candidate}.${n}" >/dev/null 2>&1; do
        n=$(( n + 1 ))
      done
      candidate="${next_version}-beta.${n}"
    fi
    full_version="$candidate"
  else
    full_version="$next_version"
  fi
fi

previous_stable_tag=""
if parent_commit="$(git rev-parse HEAD^ 2>/dev/null)"; then
  while IFS= read -r tag; do
    if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      previous_stable_tag="$tag"
    fi
  done < <(git tag --merged "$parent_commit" | sort -V)
fi

printf 'source_name=%s\n' "$source_name"
printf 'code=%s\n' "$source_code"
printf 'base=%s\n' "$base_version"
printf 'channel=%s\n' "$channel"
printf 'full_name=%s\n' "$full_version"
printf 'full_tag=v%s\n' "$full_version"
printf 'prev_stable_tag=%s\n' "$previous_stable_tag"
