import subprocess
import os

script_path = 'sleep.js'
for i in range(1, 1001):
    job_name = 'sleep_10ms_' + str(i)
    subprocess.check_output(['./wsk', 'action', 'create', job_name, script_path, '--insecure'], stderr=open(os.devnull, 'w'))