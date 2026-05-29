import { initializeApp, cert, getApps, applicationDefault } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";

if (getApps().length === 0) {
    if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
        initializeApp({ credential: applicationDefault() });
    } else if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
        const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
        initializeApp({ credential: cert(sa) });
    } else {
        throw new Error(
            "Firebase Admin credentials not configured. Set GOOGLE_APPLICATION_CREDENTIALS " +
                "to the path of a service account JSON, or FIREBASE_SERVICE_ACCOUNT_JSON to its raw contents.",
        );
    }
}

export const adminAuth = getAuth();
