#!/bin/bash
module add java/17.0.2

cp init.sh "init_${SLURM_ARRAY_TASK_ID}.sh"
sed -i -e 's/--id 0 --uniqueName 0 #>> .\/logs\/invoker0_logs.log/--id '"$SLURM_ARRAY_TASK_ID"' --uniqueName '"$SLURM_ARRAY_TASK_ID"' #>> .\/logs\/invoker'"$SLURM_ARRAY_TASK_ID"'_logs.log/g' ./init_$SLURM_ARRAY_TASK_ID.sh
exec ./init_$SLURM_ARRAY_TASK_ID.sh
