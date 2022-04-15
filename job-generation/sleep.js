function sleep(milis) {
  const date = Date.now();
  let currentDate = null;
  do {
    currentDate = Date.now();
  } while(currentDate - date < milis);
}


function main(params) {
	sleep(params.duration || 10);
	//var os = require("os");
	//var hostname = os.hostname();
	//return {'hostname': hostname };
}
