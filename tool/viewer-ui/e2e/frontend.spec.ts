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
