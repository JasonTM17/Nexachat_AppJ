#!/usr/bin/env bash

derive_release_version() {
    local version_tag="${1:-}"
    if [[ ! "$version_tag" =~ ^v?(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]]; then
        printf 'ERROR: version tag must be MAJOR.MINOR.PATCH with an optional leading v.\n' >&2
        return 1
    fi

    local major="${BASH_REMATCH[1]}"
    local minor="${BASH_REMATCH[2]}"
    local patch="${BASH_REMATCH[3]}"
    local version_name="${version_tag#v}"
    local version_code=$((major * 1000000 + minor * 1000 + patch))

    if (( version_code < 1 || version_code > 2100000000 )); then
        printf 'ERROR: derived version code must be between 1 and 2100000000.\n' >&2
        return 1
    fi

    printf '%s %s\n' "$version_name" "$version_code"
}
