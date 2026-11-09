import { expect, test, type Page } from '@playwright/test';

// run-6 is the order placed in the browser: the e2e client drives the frontend, whose fetches reach both services
async function pickBrowserScenario(page: Page) {
  await page.getByRole('button', { name: /scenario/i }).click();
  await page.getByRole('option', { name: /place-order-in-browser/ }).click();
}

const pathEdge = (page: Page, id: string) => page.locator(`.react-flow__edge-path.path-edge[data-connection="${id}"]`);
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

test('the path carries only step numbers, and a number shows its step in the side panel', async ({ page }) => {
  await page.goto('/');
  await pickBrowserScenario(page);

  const chip = page.locator('.edge-chip.on-path[data-connection="e2e-client--frontend"]');
  await expect(chip.locator('.step-badge').first()).toHaveText(/^1/); // the run starts in the browser
  await expect(chip.locator('.edge-label')).toHaveCount(0);

  await page.getByRole('button', { name: 'Step 1', exact: true }).click();
  await expect(page).toHaveURL(/\/$/); // the board stays
  const panel = page.getByRole('complementary', { name: 'Details' });
  await expect(panel).toContainText('Step 1 of');
  await expect(panel).toContainText('page load');

  await panel.getByRole('button', { name: 'Next step' }).click();
  await expect(panel).toContainText('Step 2 of');
  await expect(page.getByRole('button', { name: 'Step 2', exact: true })).toHaveAttribute('aria-pressed', 'true');

  await panel.getByRole('button', { name: 'Previous step' }).click();
  await panel.getByRole('link', { name: 'Sequence view' }).click();
  await expect(page).toHaveURL(/\/sequence\/run-6\?step=/);
  await expect(diagram(page).getByRole('button', { name: 'request load /' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('complementary', { name: 'Message details' })).toContainText('page load');
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
