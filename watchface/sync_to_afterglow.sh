#!/bin/bash
# Publishes this repo's HEAD to github.com/balajivx/afterglow under watchface/ (one commit on origin/main),
# without touching anything else there (same approach as ~/Afterglow/watch-sports/sync_to_afterglow.sh).
#   ./sync_to_afterglow.sh "commit message"
set -e
MSG="${1:?commit message}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HOME/Afterglow/project"           # a clone of balajivx/afterglow
WT="$(mktemp -d)/afterglow"
git -C "$REPO" fetch -q origin
git -C "$REPO" worktree add -q --detach "$WT" origin/main
trap 'git -C "$REPO" worktree remove --force "$WT"; git -C "$REPO" worktree prune' EXIT
rm -rf "$WT/watchface" && mkdir -p "$WT/watchface"
git -C "$HERE" archive HEAD | tar -x -C "$WT/watchface"
cd "$WT"
git add -A watchface
git diff --cached --stat | tail -1
git commit -q -m "$MSG" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git -c credential.helper= -c "credential.helper=!gh auth git-credential" push -q origin HEAD:main
git log --oneline -1
