import type { NextFunction, Request, Response } from "express";
import { OAuth2Client } from "google-auth-library";

declare module "express-serve-static-core" {
    interface Request {
        userUid?: string;
        userEmail?: string;
    }
}

const webClientId = process.env.GOOGLE_OAUTH_WEB_CLIENT_ID;
if (!webClientId) {
    throw new Error(
        "GOOGLE_OAUTH_WEB_CLIENT_ID is not set. Create a Web OAuth client in Google Cloud " +
            "Console → APIs & Services → Credentials and put its client ID here.",
    );
}

const client = new OAuth2Client(webClientId);

export async function requireAuth(req: Request, res: Response, next: NextFunction): Promise<void> {
    const header = req.header("Authorization");
    if (!header || !header.startsWith("Bearer ")) {
        res.status(401).json({ error: "missing Authorization: Bearer <google-id-token>" });
        return;
    }
    const token = header.slice("Bearer ".length).trim();
    try {
        const ticket = await client.verifyIdToken({ idToken: token, audience: webClientId });
        const payload = ticket.getPayload();
        const sub = payload?.sub;
        if (!sub) {
            res.status(401).json({ error: "token has no subject claim" });
            return;
        }
        req.userUid = sub;
        req.userEmail = payload?.email;
        next();
    } catch (e) {
        res.status(401).json({ error: "invalid or expired Google ID token" });
    }
}
