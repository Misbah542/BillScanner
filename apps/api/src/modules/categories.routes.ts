import { Router } from 'express';
import { z } from 'zod';
import { CATEGORY_TAXONOMY_VERSION } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { requireAuth } from '../middleware/auth.js';
import { validateBody, validateParams } from '../middleware/validate.js';
import { writeLimiter } from '../middleware/rateLimit.js';

export const categoriesRouter = Router();
categoriesRouter.use(requireAuth);

/** The built-in taxonomy plus anything this user added. */
categoriesRouter.get('/', async (req, res, next) => {
  try {
    const categories = await prisma.category.findMany({
      where: { OR: [{ ownerId: null }, { ownerId: req.user!.id }] },
      orderBy: [{ sortOrder: 'asc' }, { name: 'asc' }],
      select: {
        id: true,
        slug: true,
        name: true,
        iconKey: true,
        colorHex: true,
        tintHex: true,
        kind: true,
        ownerId: true
      }
    });

    res.json({
      categories: categories.map((category) => ({
        ...category,
        ownerId: undefined,
        custom: category.ownerId !== null
      })),
      taxonomyVersion: CATEGORY_TAXONOMY_VERSION
    });
  } catch (error) {
    next(error);
  }
});

const CreateBody = z.object({
  name: z.string().trim().min(1).max(60),
  iconKey: z.string().trim().max(32).default('dots'),
  colorHex: z.string().regex(/^#[0-9A-Fa-f]{6}$/).default('#5C574F'),
  tintHex: z.string().regex(/^#[0-9A-Fa-f]{6}$/).default('#F0EBE0'),
  kind: z.enum(['SPEND', 'INCOME', 'TRANSFER']).default('SPEND')
});

categoriesRouter.post('/', writeLimiter, validateBody(CreateBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof CreateBody>;
    const slug = body.name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .slice(0, 48);
    if (!slug) throw ApiError.badRequest('INVALID_NAME', 'Give the category a name with some letters in it.');

    const category = await prisma.category.create({
      data: { ...body, slug, ownerId: req.user!.id, sortOrder: 1000 },
      select: { id: true, slug: true, name: true, iconKey: true, colorHex: true, tintHex: true, kind: true }
    });
    res.status(201).json({ category: { ...category, custom: true } });
  } catch (error) {
    next(error);
  }
});

const IdParam = z.object({ id: z.string().uuid() });

categoriesRouter.delete('/:id', writeLimiter, validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const category = await prisma.category.findFirst({
      where: { id, ownerId: req.user!.id },
      select: { id: true }
    });
    if (!category) {
      throw ApiError.notFound('That category (built-in categories cannot be deleted)');
    }
    // Expenses keep existing with no category rather than disappearing.
    await prisma.category.delete({ where: { id } });
    res.status(204).end();
  } catch (error) {
    next(error);
  }
});
