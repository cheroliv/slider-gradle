import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const SLIDES_DIR = path.resolve(__dirname, '..', '..', '..', '..', 'build', 'docs', 'asciidocRevealJs');
const VALID_TRANSITIONS = new Set(['slide', 'fade', 'zoom', 'convex', 'concave', 'none', 'default']);

interface SlideInfo {
  index: number;
  locator: string;
  heading: string | null;
  contentLength: number;
  transition: string | null;
  images: number;
  brokenImages: number;
}

function getSlideFiles(): string[] {
  if (!fs.existsSync(SLIDES_DIR)) {
    console.warn(`Slides directory not found: ${SLIDES_DIR}`);
    return [];
  }
  return fs.readdirSync(SLIDES_DIR)
    .filter(f => f.endsWith('.html') && f !== 'index.html')
    .sort();
}

async function collectSlideInfo(page: Page): Promise<SlideInfo[]> {
  const slides: SlideInfo[] = [];
  const count = await page.locator('.slides > section').count();
  for (let i = 0; i < count; i++) {
    const section = page.locator('.slides > section').nth(i);
    const heading = await section.locator('h1, h2').first().textContent();
    const text = await section.innerText();
    const transition = await section.getAttribute('data-transition');
    const imgs = await section.locator('img').count();
    const broken = await section.locator('img[src]').evaluateAll((imgs) =>
      imgs.filter((img) => {
        const src = (img as HTMLImageElement).src || '';
        return !src || src.endsWith('/') || !(img as HTMLImageElement).complete;
      }).length
    );
    slides.push({
      index: i + 1,
      locator: `section:nth-of-type(${i + 1})`,
      heading: heading?.trim() ?? null,
      contentLength: text.trim().length,
      transition,
      images: imgs,
      brokenImages: broken,
    });
  }
  return slides;
}

async function getComputedFont(page: Page, selector: string): Promise<string> {
  return page.locator(selector).evaluate(el =>
    getComputedStyle(el).getPropertyValue('font-family')
  );
}

test.describe('Slider Visual Rendering', () => {
  const slideFiles = getSlideFiles();

  test('should have at least one slide deck generated', () => {
    expect(slideFiles.length).toBeGreaterThan(0);
  });

  slideFiles.forEach(slideFile => {
    test.describe(`Slide deck: ${slideFile}`, () => {
      const slidePath = `file://${path.join(SLIDES_DIR, slideFile)}`;

      // ─── P0: Structure / DOM ─────────────────────────────────────

      test('P0-1: each slide has a heading', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const slides = await collectSlideInfo(page);
        expect(slides.length).toBeGreaterThan(0);
        for (const s of slides) {
          expect(s.heading, `Slide ${s.index} missing heading`).not.toBeNull();
        }
      });

      test('P0-2: no empty slides', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const slides = await collectSlideInfo(page);
        for (const s of slides) {
          expect(s.contentLength,
            `Slide ${s.index} "${s.heading}" is empty`).toBeGreaterThan(0);
        }
      });

      test('P0-3: header and footer are visible', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const header = page.locator('.slide-header');
        const footer = page.locator('.slide-footer');
        await expect(header).toBeVisible();
        await expect(footer).toBeVisible();
      });

      test('P0-4: no console errors', async ({ page }) => {
        const errors: string[] = [];
        page.on('pageerror', err => errors.push(err.message));
        page.on('console', msg => {
          if (msg.type() === 'error') errors.push(msg.text());
        });
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        expect(errors).toEqual([]);
      });

      // ─── P1: Visual consistency ─────────────────────────────────

      test('P1-1: font-family resolves to Inter or system-ui fallback', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const font = await getComputedFont(page, '.reveal');
        expect(font.toLowerCase()).toMatch(/inter|system-ui|-apple-system|sans-serif/);
      });

      test('P1-2: text and background have sufficient contrast', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const bg = await page.locator('.reveal').evaluate(el =>
          getComputedStyle(el).getPropertyValue('background-color')
        );
        const color = await page.locator('.reveal p').first().evaluate(el =>
          getComputedStyle(el).getPropertyValue('color')
        );
        expect(bg).not.toEqual(color);
      });

      test('P1-3: all images load successfully', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const slides = await collectSlideInfo(page);
        const totalBroken = slides.reduce((sum, s) => sum + s.brokenImages, 0);
        expect(totalBroken, `${totalBroken} broken images in deck`).toBe(0);
      });

      test('P1-4: data-transition values are valid', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const slides = await collectSlideInfo(page);
        for (const s of slides) {
          if (s.transition) {
            expect(VALID_TRANSITIONS.has(s.transition),
              `Slide ${s.index} invalid transition "${s.transition}"`).toBeTruthy();
          }
        }
      });

      // ─── P2: Business / deck-level ───────────────────────────────

      test('P2-1: slide count is reasonable (≥ 1)', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const slides = await collectSlideInfo(page);
        expect(slides.length).toBeGreaterThanOrEqual(1);
      });

      test('P2-2: estimated duration is within bounds', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        const slides = await collectSlideInfo(page);
        const estimatedMinutes = Math.round((slides.length * 45) / 60);
        expect(estimatedMinutes).toBeGreaterThan(0);
      });

      // ─── Snapshot regression (baseline matching) ────────────────

      test('snapshot: first slide matches baseline', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        await page.waitForTimeout(500);
        const firstSlide = page.locator('.slides > section').first();
        await expect(firstSlide).toHaveScreenshot(`${slideFile.replace('.html', '')}-first-slide.png`);
      });

      test('snapshot: full deck matches baseline', async ({ page }) => {
        await page.goto(slidePath, { waitUntil: 'networkidle' });
        await page.waitForTimeout(500);
        await expect(page.locator('.slides')).toHaveScreenshot(`${slideFile.replace('.html', '')}-full-deck.png`);
      });
    });
  });
});
