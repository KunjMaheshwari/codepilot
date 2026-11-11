#!/bin/bash
git checkout -b recovery-baseline || git checkout recovery-baseline

# Ensure proper ignores
cat << 'IGNORE' > .gitignore
# Logs
logs
*.log
npm-debug.log*
yarn-debug.log*
yarn-error.log*
lerna-debug.log*

# Coverage directory used by tools like istanbul
coverage
*.lcov

# nyc test coverage
.nyc_output

# Compiled binary addons (https://nodejs.org/api/addons.html)
build/Release

# Dependency directories
node_modules/
frontend/node_modules/

# TypeScript cache
*.tsbuildinfo

# Optional npm cache directory
.npm

# Optional eslint cache
.eslintcache

# dotenv environment variables file
.env
.env.test
.env.local

# parcel-bundler cache (https://parceljs.org/)
.cache

# Next.js build output
.next
frontend/.next/
frontend/out/
out/

# Nuxt.js build / generate output
.nuxt
dist

# Serverless directories
.serverless/

# Java/Maven/Spring Boot
target/
backend/target/
*.class
*.jar
*.war
*.ear
*.nar
*.log

# IDE
.idea/
*.iml
.vscode/
.DS_Store

# Test outputs
backend/mvn_output*.log
backend/codepilot*.log
/tmp/*.log
/tmp/*.txt
IGNORE

# Untrack any accidentally tracked build outputs
git rm -r --cached frontend/node_modules/ 2>/dev/null || true
git rm -r --cached frontend/.next/ 2>/dev/null || true
git rm -r --cached backend/target/ 2>/dev/null || true
git rm -r --cached target/ 2>/dev/null || true
git rm -r --cached node_modules/ 2>/dev/null || true

# Add changes and commit to clear workspace diff
git add .
git commit -m "chore: establish CodePilot development baseline and ignore artifacts"
