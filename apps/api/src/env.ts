import { z } from 'zod';

/**
 * Configuration is validated once, at boot. A missing or malformed variable
 * crashes the process with a readable list instead of surfacing as a confusing
 * 500 on someone's first request.
 */
const Schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(4000),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
  PUBLIC_BASE_URL: z.string().url().default('http://localhost:4000'),
  APP_SHARE_BASE_URL: z.string().url().default('http://localhost:4000/t'),

  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),

  JWT_SECRET: z.string().min(32, 'JWT_SECRET must be at least 32 characters'),
  ACCESS_TOKEN_TTL: z.coerce.number().int().positive().default(900),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(60),
  OTP_TTL_SECONDS: z.coerce.number().int().positive().default(600),
  /** Comma-separated: the Android client id, and the web one if there is one. */
  GOOGLE_CLIENT_IDS: z
    .string()
    .default('')
    .transform((value) => value.split(',').map((s) => s.trim()).filter(Boolean)),

  OCR_PROVIDER: z.enum(['stub', 'google-vision', 'textract']).default('stub'),
  GOOGLE_VISION_API_KEY: z.string().optional(),
  SCAN_MAX_BYTES: z.coerce.number().int().positive().default(12 * 1024 * 1024),

  STORAGE_DRIVER: z.enum(['local', 's3']).default('local'),
  STORAGE_LOCAL_DIR: z.string().default('.storage'),
  S3_BUCKET: z.string().optional(),
  S3_REGION: z.string().optional(),
  S3_ENDPOINT: z.string().optional(),

  FCM_SERVER_KEY: z.string().optional(),
  SMTP_URL: z.string().optional(),
  SMS_GATEWAY_URL: z.string().optional(),
  SMS_GATEWAY_KEY: z.string().optional()
});

export type Env = z.infer<typeof Schema>;

function load(): Env {
  const parsed = Schema.safeParse(process.env);
  if (parsed.success) return parsed.data;

  const lines = parsed.error.issues.map((issue) => `  ${issue.path.join('.')}: ${issue.message}`);
  throw new Error(`Invalid configuration:\n${lines.join('\n')}\n\nSee apps/api/.env.example.`);
}

export const env: Env = load();

export const isProduction = env.NODE_ENV === 'production';
export const isTest = env.NODE_ENV === 'test';

/** Google sign-in is only offered when a client id was configured. */
export const googleSignInEnabled = env.GOOGLE_CLIENT_IDS.length > 0;
