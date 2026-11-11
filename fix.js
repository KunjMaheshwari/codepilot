const fs = require('fs');
const cp = require('child_process');

try {
    const orchestratorCode = cp.execSync('git show 3383d51ddeb2268bd7b56f0663a984fbb86c4123:backend/src/main/java/com/codepilot/service/CodeReviewOrchestrator.java', { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] });
    fs.writeFileSync('backend/src/main/java/com/codepilot/service/CodeReviewOrchestrator.java', orchestratorCode);
} catch (e) {}

try {
    const jobServiceCode = cp.execSync('git show 3383d51ddeb2268bd7b56f0663a984fbb86c4123:backend/src/main/java/com/codepilot/service/ReviewJobService.java', { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] });
    fs.writeFileSync('backend/src/main/java/com/codepilot/service/ReviewJobService.java', jobServiceCode);
} catch (e) {}
