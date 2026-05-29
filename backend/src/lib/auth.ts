import type { NextFunction, Request, Response } from "express";
import { adminAuth } from "./firebase.js";

declare module "express-serve-static-core" {
    interface Request {
        userUid?: string;
    }
}

export async function requireAuth(req: Request, res: Response, next: NextFunction): Promise<void> {
    const header = req.header("Authorization");
    if (!header || !header.startsWith("Bearer ")) {
        res.status(401).json({ error: "missing Authorization: Bearer <id-token>" });
        return;
    }
    const token = header.slice("Bearer ".length).trim();
    try {
        const decoded = await adminAuth.verifyIdToken(token);
        req.userUid = decoded.uid;
        next();
    } catch (e) {
        res.status(401).json({ error: "invalid or expired ID token" });
    }
}
