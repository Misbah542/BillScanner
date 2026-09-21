import { Router } from 'express';
import { CATEGORY_TAXONOMY_VERSION, SMS_RULES_VERSION } from '@snaptab/shared';
import { env, googleSignInEnabled } from '../env.js';
import { alertsRouter } from './alerts.routes.js';
import { authRouter } from './auth.routes.js';
import { categoriesRouter } from './categories.routes.js';
import { expensesRouter } from './expenses.routes.js';
import { groupsRouter } from './groups.routes.js';
import { insightsRouter } from './insights.routes.js';
import { scansRouter } from './scans.routes.js';
import { settlementsRouter } from './settlements.routes.js';
import { shareRouter } from './share.routes.js';
import { usersRouter } from './users.routes.js';

export const apiRouter = Router();

/**
 * What the app asks for on launch, so it knows which sign-in methods to offer and
 * whether its bundled rule files are stale.
 */
apiRouter.get('/config', (_req, res) => {
  res.json({
    auth: { email: true, phone: true, google: googleSignInEnabled },
    scan: { maxBytes: env.SCAN_MAX_BYTES, provider: env.OCR_PROVIDER },
    rules: { sms: SMS_RULES_VERSION, categories: CATEGORY_TAXONOMY_VERSION },
    shareBaseUrl: env.APP_SHARE_BASE_URL
  });
});

apiRouter.use('/auth', authRouter);
apiRouter.use('/users', usersRouter);
apiRouter.use('/groups', groupsRouter);
apiRouter.use('/expenses', expensesRouter);
apiRouter.use('/scans', scansRouter);
apiRouter.use('/alerts', alertsRouter);
apiRouter.use('/settlements', settlementsRouter);
apiRouter.use('/categories', categoriesRouter);
apiRouter.use('/insights', insightsRouter);
apiRouter.use('/share-links', shareRouter);

export { publicShareRouter } from './share.routes.js';
