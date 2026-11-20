#!/bin/bash
# Publishes this repo's HEAD to github.com/balajivx/afterglow under watchsports/ (one commit on origin/main),
# without touching anything else there. The monthly photo sets (wallpapers/<YYYY-MM>/, current.json,
# history.txt) belong to the repo's GitHub Action after the first push, so they're kept as they are there.
#   ./sync_to_afterglow.sh "commit message"            (WITH_WORKFLOW=1 also installs the monthly Action)
set -e
MSG="${1:?commit message}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HOME/Afterglow/project"           # a clone of balajivx/afterglow
WT="$(mktemp -d)/afterglow"
git -C "$REPO" fetch -q origin
git -C "$REPO" worktree add -q --detach "$WT" origin/main
trap 'git -C "$REPO" worktree remove --force "$WT"; git -C "$REPO" worktree prune' EXIT
KEEP="$(mktemp -d)"
if [ -f "$WT/watchsports/wallpapers/current.json" ]; then   # the Action's sets win over ours
  cp -R "$WT/watchsports/wallpapers/." "$KEEP/"
fi
rm -rf "$WT/watchsports" && mkdir -p "$WT/watchsports"
git -C "$HERE" archive HEAD | tar -x -C "$WT/watchsports"
rm -rf "$WT/watchsports/build"
if [ -f "$KEEP/current.json" ]; then
  find "$WT/watchsports/wallpapers" -maxdepth 1 \( -name '20[0-9][0-9]-[0-9][0-9]' -o -name current.json -o -name history.txt \) -exec rm -rf {} +
  ( cd "$KEEP" && cp -R 20[0-9][0-9]-[0-9][0-9] current.json history.txt "$WT/watchsports/wallpapers/" )
fi
cd "$WT"
git add -A watchsports
# The monthly Action's file: pushing it needs the GitHub login's "workflow" scope (gh auth refresh -s workflow).
if [ -n "$WITH_WORKFLOW" ]; then
  mkdir -p .github/workflows
  cp "$HERE/wallpapers/workflow/watch-wallpapers.yml" .github/workflows/watch-wallpapers.yml
  git add .github/workflows/watch-wallpapers.yml
fi
git diff --cached --stat | tail -1
git commit -q -m "$MSG" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git -c credential.helper= -c "credential.helper=!gh auth git-credential" push -q origin HEAD:main
git log --oneline -1
