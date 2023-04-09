#!/bin/bash

DESIRED_JOB_COUNT=50

BASE_DIR=$(dirname $(readlink -f $0))
JOB_TEMPLATE="job_template.sh"

while true; do
	JOB_COUNT=$(squeue -u $USER -h | wc -l)
	RUNNING_JOB_COUNT=$(squeue -u $USER -t r -h | wc -l)
	echo "Current number of jobs: ${JOB_COUNT}/${DESIRED_JOB_COUNT}, running: ${RUNNING_JOB_COUNT}/${JOB_COUNT}, press [CTRL+C] to stop monitoring..."

	if [ "${JOB_COUNT}" -lt "${DESIRED_JOB_COUNT}" ]; then
		let "difference=${DESIRED_JOB_COUNT}-${JOB_COUNT}"
		echo "Adding ${difference} jobs.."
		for i in $(seq ${difference}); do
			echo "Adding job ${i}/${difference} ... "
			sbatch ${BASE_DIR}/${JOB_TEMPLATE}
		done
	fi
	sleep 15s # allow slurm to catch up with jobs
done
