#!/bin/bash
#SBATCH --job-name=invoker
#SBATCH --ntasks-per-node=1                    # Run on a single CPU
#SBATCH --nodes=1
#SBATCH --array=2
#SBATCH --time=00:10:00               # Time limit hrs:min:sec
#SBATCH --output=invoker_%j.log   # Standard output and error log
#SBATCH --error=invoker_%j.err   # Standard output and error log
#SBATCH --exclude=p2286
#SBATCH --mem=8192m


exec /net/archive/groups/plggwhisk/native_invoker/bin/init.sh

