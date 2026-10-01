#!/usr/bin/env sh
# One-command on-sale stampede against a running booking-service.
#
#   ADMIN_SECRET=... ./burst.sh <BASE_URL> [--requests 20000 --concurrency 500 ...]
#
# Runs burst/burst.py with uv (https://docs.astral.sh/uv/), which also provides a suitable
# Python and the script's dependencies. If uv is missing, offers to install it first.
set -e

UV_INSTALL_CMD='curl -LsSf https://astral.sh/uv/install.sh | sh'

find_uv() {
    if command -v uv >/dev/null 2>&1; then
        command -v uv
    elif [ -x "$HOME/.local/bin/uv" ]; then
        echo "$HOME/.local/bin/uv"
    fi
}

UV=$(find_uv)

if [ -z "$UV" ]; then
    echo "This script needs uv (a Python package runner), which was not found."
    if [ -t 0 ]; then
        printf "Install uv now into ~/.local/bin using the official installer? [y/N] "
        read -r answer
    else
        answer=""
    fi
    case "$answer" in
        y|Y|yes|YES)
            if ! command -v curl >/dev/null 2>&1; then
                echo "curl is required to install uv. Install uv manually: https://docs.astral.sh/uv/" >&2
                exit 1
            fi
            sh -c "$UV_INSTALL_CMD"
            UV=$(find_uv)
            if [ -z "$UV" ]; then
                echo "uv was installed but could not be found; open a new shell and re-run." >&2
                exit 1
            fi
            ;;
        *)
            echo "Install it with:  $UV_INSTALL_CMD" >&2
            echo "(or: brew install uv) and re-run this script." >&2
            exit 1
            ;;
    esac
fi

exec "$UV" run "$(dirname "$0")/burst/burst.py" "$@"
