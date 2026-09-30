# SignVibe AI Supabase Function

Deploy:

```bash
supabase functions deploy signvibe-ai
```

Set the server-side key:

```bash
supabase secrets set GEMINI_API_KEY=your_key_here
```

Android local config goes in ignored `local.properties`:

```properties
supabase.aiFunctionUrl=https://YOUR_PROJECT_REF.functions.supabase.co/signvibe-ai
supabase.anonKey=YOUR_SUPABASE_ANON_KEY
```

Do not commit `GEMINI_API_KEY`.
