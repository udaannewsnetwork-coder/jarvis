## Vercel setup and adding the OpenAI API key

To deploy this project on Vercel and let it call OpenAI, you need to add your OpenAI API key as an environment variable:

1. Go to https://vercel.com and import your GitHub repository (udaannewsnetwork-coder/jarvis).
2. In the Vercel dashboard, open your project, then go to Settings → Environment Variables.
3. Add a new variable:
   - Name: `OPENAI_API_KEY`
   - Value: your OpenAI secret key (starts with `sk-...`)
   - Environment: set for `Production` and `Preview` (or `Development` if you want)
4. Save and redeploy the project.

Locally, you can set it in `.env.local`:

```
OPENAI_API_KEY=sk-...
```

Then run `npm run dev` to test locally.

Notes:
- Never commit your real API key to the repository.
- Monitor usage in your OpenAI dashboard to avoid unexpected charges.
