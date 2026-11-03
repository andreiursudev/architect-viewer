import { expect, test, type Page } from '@playwright/test';

// run-6 is the order placed in the browser: the e2e client drives the frontend, whose fetches reach both services
async function pickBrowserScenario(page: Page) {
  await page.getByRole('button', { name: /scenario/i }).click();
  await page.getByRole('option', { name: /place-order-in-browser/ }).click();
}

const pathEdge = (page: Page, id: string) => page.locator(`.react-flow__edge-path.path-edge[id="${id}"]`);
const heads = (page: Page) => page.locator('.seq-head');
const diagram = (page: Page) => page.getByRole('group', { name: 'Sequence diagram' });

test('board shows the frontend between the e2e client and the services', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.component-node[title="frontend"]')).toBeVisible();
  await expect(page.locator('.component-node.type-frontend svg[data-icon="frontend"]')).toHaveCount(1);

  await pickBrowserScenario(page);

  for (const id of ['e2e-client--frontend', 'frontend--orders-service', 'frontend--inventory-service']) {
    await expect(pathEdge(page, id)).toHaveCount(1);
  }
});

test('hop details stay open while the pointer moves from the chip onto them, and scroll there', async ({ page }) => {
  await page.goto('/');
  await pickBrowserScenario(page);

  const chip = page.locator('.edge-chip.on-path').filter({ hasText: 'load /' });
  await chip.locator('.edge-label').hover();
  const details = chip.locator('.hop-details');
  await expect(details).toBeVisible();

  // straight down from the chip, through the gap above the details, into them
  const from = (await chip.locator('.edge-label').boundingBox())!;
  const to = (await details.boundingBox())!;
  const x = from.x + from.width / 2;
  await page.mouse.move(x, to.y + 20, { steps: 20 });
  await expect(details).toBeVisible();

  // the wheel scrolls the details, not the board's zoom
  const zoom = await page.locator('.react-flow__viewport').getAttribute('style');
  await page.mouse.wheel(0, 200);
  await expect.poll(() => details.evaluate((d) => d.scrollTop)).toBeGreaterThan(0);
  await expect(page.locator('.react-flow__viewport')).toHaveAttribute('style', zoom!);
});

test('sequence shows the browser steps first', async ({ page }) => {
  await page.goto('/sequence/run-6');
  await expect(diagram(page)).toBeVisible();

  await expect(heads(page).nth(0)).toHaveAttribute('title', 'e2e-client');
  await expect(heads(page).nth(1)).toHaveAttribute('title', 'frontend');
  await expect(diagram(page).getByRole('button').first()).toHaveAccessibleName('request load /');

  await diagram(page).getByRole('button', { name: 'request submit order-form' }).click();
  const details = page.getByRole('complementary', { name: 'Message details' });
  await expect(details).toContainText('Target');
  await expect(details).toContainText('order-form');
});

test('frontend has no class toggle', async ({ page }) => {
  await page.goto('/sequence/run-6');
  await expect(diagram(page)).toBeVisible();

  const frontend = page.locator('.seq-head[title="frontend"]');
  await expect(frontend).toBeVisible();
  await expect(frontend.getByRole('button')).toHaveCount(0);
  await expect(page.locator('.seq-head[title="orders-service"]').getByRole('button', { name: '▸ 4 classes' })).toBeVisible();
});

test('sequence shows a screenshot beside each browser step and in the details', async ({ page }) => {
  await page.goto('/sequence/run-6');
  await expect(diagram(page)).toBeVisible();

  const thumbnails = diagram(page).getByRole('button', { name: /^screenshot / });
  await expect(thumbnails).toHaveCount(5); // one per ui step: three screenshots, two of them shared by two steps
  const loaded = await thumbnails.locator('image').evaluateAll((images) =>
    Promise.all(
      images.map(
        (image) =>
          new Promise<number>((resolve) => {
            const probe = new Image();
            probe.onload = () => resolve(probe.naturalWidth);
            probe.onerror = () => resolve(0);
            probe.src = image.getAttribute('href')!;
          }),
      ),
    ),
  );
  expect(loaded.every((width) => width > 0)).toBe(true);

  await diagram(page).getByRole('button', { name: 'screenshot load /' }).click();
  await expect(diagram(page).getByRole('button', { name: 'request load /' })).toHaveAttribute('aria-pressed', 'true');
  const details = page.getByRole('complementary', { name: 'Message details' });
  const image = details.getByRole('img', { name: 'Screenshot after load /' });
  await expect(image).toBeVisible();
  await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);

  await image.click();
  const carousel = page.getByRole('dialog', { name: 'Screenshot' });
  await expect(carousel).toBeVisible();
  await expect(carousel.getByText(/^load \/ · 1 of 5$/)).toBeVisible();
  await expect(carousel.getByRole('button', { name: 'Previous screenshot' })).toBeDisabled();

  // → walks the browser steps and selects each one in the sequence behind
  await page.keyboard.press('ArrowRight');
  await expect(carousel.getByText(/ · 2 of 5$/)).toBeVisible();
  const second = (await carousel.locator('.screenshot-caption').textContent())!.replace(/ · 2 of 5$/, '');
  await expect(diagram(page).getByRole('button', { name: `request ${second}` })).toHaveAttribute('aria-pressed', 'true');
  await carousel.getByRole('button', { name: 'Next screenshot' }).click();
  await expect(carousel.getByText(/ · 3 of 5$/)).toBeVisible();
  await page.keyboard.press('ArrowLeft');
  await expect(carousel.getByText(/ · 2 of 5$/)).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(carousel).toBeHidden();
  await expect(details.getByRole('heading', { level: 2 })).toHaveText(second);
});
