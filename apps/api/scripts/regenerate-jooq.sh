#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
api_dir="$(cd -- "${script_dir}/.." && pwd)"
mode="${1:-check}"

case "${mode}" in
	check|update)
		;;
	*)
		echo "usage: $0 [check|update]" >&2
		exit 2
		;;
esac

container_name="plot-jooq-codegen-$$"
container_id=""
temp_dir=""

cleanup() {
	if [[ -n "${container_id}" ]]; then
		docker rm --force "${container_id}" >/dev/null 2>&1 || true
	fi
	if [[ -n "${temp_dir}" ]]; then
		rm -rf -- "${temp_dir}"
	fi
}
trap cleanup EXIT

container_id="$(docker run --detach --rm \
	--name "${container_name}" \
	--publish 127.0.0.1::5432 \
	--env POSTGRES_DB=plot \
	--env POSTGRES_PASSWORD=secret \
	--env POSTGRES_USER=plot \
	postgres:16)"

host_port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "${container_id}")"
for attempt in {1..60}; do
	if docker exec "${container_id}" pg_isready -U plot -d plot >/dev/null 2>&1; then
		break
	fi
	if [[ "${attempt}" == 60 ]]; then
		echo "PostgreSQL 16 did not become ready" >&2
		exit 1
	fi
	sleep 1
done

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/plot-jooq.XXXXXX")"
generated_dir="${temp_dir}/generated"
second_generated_dir="${temp_dir}/generated-second"
mkdir -p "${generated_dir}"

run_codegen() {
	local output_dir="$1"
	(
	cd "${api_dir}"
	JOOQ_CODEGEN_JDBC_URL="jdbc:postgresql://127.0.0.1:${host_port}/plot" \
	JOOQ_CODEGEN_JDBC_USER=plot \
	JOOQ_CODEGEN_JDBC_PASSWORD=secret \
	./gradlew --no-daemon \
		-PjooqOutputDir="${output_dir}" \
		flywayMigrate flywayValidate jooqCodegen
	)
}

run_codegen "${generated_dir}"
mkdir -p "${second_generated_dir}"
run_codegen "${second_generated_dir}"

if ! diff -ru --no-dereference "${generated_dir}" "${second_generated_dir}"; then
	echo "jOOQ generation is not byte-stable across two runs" >&2
	exit 1
fi

baseline_dir="${api_dir}/src/generated/kotlin"
if [[ "${mode}" == update ]]; then
	rm -rf -- "${baseline_dir}"
	mkdir -p "${baseline_dir}"
	cp -R "${generated_dir}/." "${baseline_dir}/"
	echo "Updated ${baseline_dir}"
	exit 0
fi

if ! diff -ru --no-dereference "${baseline_dir}" "${generated_dir}"; then
	echo "jOOQ generated sources differ from the checked-in baseline" >&2
	echo "Run $0 update after reviewing the migration/schema change" >&2
	exit 1
fi

echo "jOOQ generated sources match the checked-in baseline"
