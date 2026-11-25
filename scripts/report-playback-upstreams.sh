#!/usr/bin/env bash
# Read only: no checkout, execution, merge, credentials, or playback requests.
set -euo pipefail
repos=(
  beilunyang/openhongguo
  kadaliao/openhongguo
  kadaliao/kaixinguo-android
  N3urda/hongguoTV
  OOIUa/hongguo-android
  tsukiyo-zero/hongguoTV
  wjsqqj/aquan-duanju
)
echo '## Playback upstream review (manual compatibility review required)'
echo
echo 'A listed change is not proof of live playback success.'
for repo in "${repos[@]}"; do
  echo
  printf '### %s\n' "$repo"
  if info=$(gh api "repos/$repo" 2>/dev/null); then
    branch=$(jq -r '.default_branch // empty' <<<"$info")
  else
    echo 'Repository metadata unavailable; retry next scheduled run.'
    continue
  fi
  if [[ -n "$branch" ]]; then
    if commits=$(gh api --method GET "repos/$repo/commits" -f sha="$branch" -f per_page=20 2>/dev/null); then
      jq -r '.[] | select(.commit.message | test("playback|video.model|signature|signing|codec|播放|取址|签名|空响应|解码"; "i")) | "- " + (.commit.author.date // "") + " [" + .sha[0:8] + "](" + .html_url + ") " + (.commit.message | split("\n")[0] | gsub("[<>]"; "") | .[0:180])' <<<"$commits"
    else
      echo 'Commit history unavailable.'
    fi
  fi
  if prs=$(gh api "repos/$repo/pulls?state=open&per_page=30" 2>/dev/null); then
    jq -r '.[] | select(.title | test("playback|signing|signature|codec|播放|取址|签名|空响应|解码"; "i")) | "- Open PR [#" + (.number|tostring) + "](" + .html_url + ") " + (.title | gsub("[<>]"; "") | .[0:180])' <<<"$prs"
  else
    echo 'Pull request list unavailable.'
  fi
done
