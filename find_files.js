const cp = require('child_process');

try {
  const log = cp.execSync('git log --all --full-history --name-status --format="%H %s"', { maxBuffer: 50 * 1024 * 1024, encoding: 'utf8' });

  const lines = log.split('\n');
  let currentCommit = '';

  const results = {};

  for (const line of lines) {
    if (line.match(/^[a-f0-9]{40} /)) {
      currentCommit = line;
    } else {
        if (line.includes('CodeReviewOrchestrator') || line.includes('ReviewJobService')) {
          console.log(`${currentCommit} -> ${line}`);
        }
    }
  }
} catch (e) {
  console.error("Error:", e.message);
}
