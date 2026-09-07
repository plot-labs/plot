#!/usr/bin/env bash
# Load apps/api/.env.local safely (values may contain ?, &, spaces) then exec.
set -euo pipefail

api_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${api_dir}/.env.local"

if [[ ! -f "${env_file}" ]]; then
	echo "missing ${env_file}" >&2
	exit 1
fi

if [[ $# -eq 0 ]]; then
	echo "usage: $0 <command> [args...]" >&2
	exit 2
fi

while IFS= read -r line || [[ -n "${line}" ]]; do
	# trim CR and leading/trailing whitespace
	line="${line%$'\r'}"
	[[ -z "${line}" || "${line}" =~ ^[[:space:]]*# ]] && continue
	[[ "${line}" != *=* ]] && continue
	key="${line%%=*}"
	value="${line#*=}"
	# strip matching single/double quotes around the whole value
	if [[ "${value}" =~ ^\".*\"$ ]]; then
		value="${value:1:${#value}-2}"
	elif [[ "${value}" =~ ^\'.*\'$ ]]; then
		value="${value:1:${#value}-2}"
	fi
	export "${key}=${value}"
done < "${env_file}"

cd "${api_dir}"
exec "$@"
