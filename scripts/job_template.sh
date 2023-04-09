#!/bin/bash

#SBATCH -N 1
#SBATCH -n 1
#SBATCH --cpus-per-task 24
#SBATCH -p plgrid
#SBATCH -A plgccbmc11-cpu
#SBATCH --time 01:00:00
##SBATCH --time-min 00:01:00
##SBATCH --mem 8000

##SBATCH -o /net/archive/groups/plggwhisk/openwhisk-singularity/native-invoker/jobs_out/invoker_%j.out
##SBATCH -e /net/archive/groups/plggwhisk/openwhisk-singularity/native-invoker/jobs_out/invoker_%j.out

module add java/17.0.2

#INVOKER_ID=20
#INVOKER_ID="${1-generated_$RANDOM}"
INVOKER_ID="${SLURM_JOB_ID}"
BASE_PATH="/net/people/plgrid/plgvarsill/scripts"

cd ${BASE_PATH}
source ${BASE_PATH}/environment-variables.sh
export INVOKER_OPTS="$INVOKER_OPTS $(${BASE_PATH}/transformEnvironment.sh)"

${BASE_PATH}/native_invoker/bin/invoker --uniqueName ${INVOKER_ID}


