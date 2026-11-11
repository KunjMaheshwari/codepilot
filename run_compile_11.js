const cp = require('child_process');
const fs = require('fs');

try {
    cp.execSync('cd backend && mvn clean compile > /tmp/c11.log 2>&1');
} catch (e) {}

const log = fs.readFileSync('/tmp/c11.log', 'utf8');
const lines = log.split('\n');
const errIdx = lines.findIndex(l => l.includes('COMPILATION ERROR'));
if (errIdx !== -1) {
    fs.writeFileSync('/tmp/c11_res.txt', lines.slice(errIdx, errIdx + 20).join('\n'));
} else {
    fs.writeFileSync('/tmp/c11_res.txt', 'SUCCESS');
}
