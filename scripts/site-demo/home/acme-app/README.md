# Acme App

A small web app with email sign-in and team workspaces.

## Getting started

```sh
npm install
npm run dev     # http://localhost:3000
npm test
```

## Layout

| Folder | What lives there |
| --- | --- |
| `src/auth` | Sessions, tokens and sign-in |
| `src/api` | HTTP handlers |
| `docs` | Design notes |

## Sessions

Access tokens last **15 minutes**. An expired token is refreshed
once with the refresh token; a revoked one signs the user out.

- [x] Refresh expired tokens
- [x] Reject revoked tokens
- [ ] Rotate refresh tokens
