
## GitHub OAuth Configuration

To run CodePilot locally, you must configure a GitHub OAuth Application:

1. Go to your GitHub account settings -> Developer settings -> OAuth Apps.
2. Click "New OAuth App".
3. Set the following values:
   - Application name: `CodePilot (Local)`
   - Homepage URL: `http://localhost:3000`
   - Authorization callback URL: `http://localhost:8080/login/oauth2/code/github`
4. Generate a Client Secret.
5. Add the Client ID and Client Secret to your environment variables or local properties file.

The application requires `repo` permissions to access pull requests and post review comments.
