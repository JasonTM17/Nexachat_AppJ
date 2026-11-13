#!/usr/bin/env bash

# Resolve the same explicit-property > android/local.properties > Gradle
# default order used by android/app/build.gradle. The signer passes the result
# back as -PapiBaseUrl so external Gradle properties cannot change the checked value.
resolve_effective_api_base_url() {
    local override_set="$1"
    local override_value="$2"
    local local_properties="$3"
    local local_value

    if [[ "$override_set" == true ]]; then
        printf '%s' "$override_value"
        return 0
    fi
    if [[ -f "$local_properties" ]] \
            && local_value="$(release_read_local_api_base_url "$local_properties")"; then
        printf '%s' "$local_value"
        return 0
    fi
    printf '%s' 'http://10.0.2.2:8080'
}

release_read_local_api_base_url() {
    local properties_file="$1"
    awk '
        {
            line = $0
            sub(/\r$/, "", line)
            if (line ~ /^[[:space:]]*[#!]/) next
            sub(/^[[:space:]]*/, "", line)
            if (line ~ /^API_BASE_URL[[:space:]]*=/) {
                sub(/^API_BASE_URL[[:space:]]*=[[:space:]]*/, "", line)
            } else if (line ~ /^API_BASE_URL[[:space:]]*:/) {
                sub(/^API_BASE_URL[[:space:]]*:[[:space:]]*/, "", line)
            } else if (line ~ /^API_BASE_URL[[:space:]]+/) {
                sub(/^API_BASE_URL[[:space:]]+/, "", line)
            } else {
                next
            }
            value = line
            found = 1
        }
        END {
            if (found) printf "%s", value
            else exit 1
        }
    ' "$properties_file"
}

release_api_base_url_summary() {
    local api_base_url="$1"
    local scheme='unknown'
    local authority=''
    local host='unknown'

    if [[ "$api_base_url" =~ ^([[:alpha:]][[:alnum:].+-]*)://([^/?#[:space:]]+)([^[:space:]]*)$ ]]; then
        scheme="${BASH_REMATCH[1]}"
        authority="${BASH_REMATCH[2]}"
        authority="${authority##*@}"
        if [[ "$authority" =~ ^([A-Za-z0-9.-]+)(:[0-9]+)?$ ]]; then
            host="${BASH_REMATCH[1]}"
        elif [[ "$authority" =~ ^(\[[0-9A-Fa-f:.]+\])(:[0-9]+)?$ ]]; then
            host="${BASH_REMATCH[1]}"
        fi
    fi

    printf 'scheme=%s host=%s' "$scheme" "$host"
}

guard_release_api_base_url() {
    local api_base_url="$1"
    local allow_http_smoke="$2"
    local summary
    summary="$(release_api_base_url_summary "$api_base_url")"
    printf 'API_BASE_URL: %s\n' "$summary"

    if [[ "$api_base_url" == https://* && "$summary" == 'scheme=https host='* ]] \
            && [[ "$summary" != *'host=unknown' ]]; then
        return 0
    fi
    if [[ "$allow_http_smoke" == true && "$api_base_url" == http://* \
            && "$summary" == 'scheme=http host='* && "$summary" != *'host=unknown' ]]; then
        printf 'WARNING: HTTP is allowed only for this local smoke build.\n' >&2
        return 0
    fi

    printf 'ERROR: signed releases require an API_BASE_URL with https://; use --allow-http-smoke only for a local smoke build.\n' >&2
    return 1
}
