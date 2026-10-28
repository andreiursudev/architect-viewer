import { expect, test, type Page } from '@playwright/test';

async function pickOutOfStock(page: Page) {
  await page.getByRole('button', { name: /scenario/i }).click();
  await page.getByRole('option', { name: /create-order-out-of-stock/ }).click();
}

const node = (page: Page, id: string) => page.locator(`.component-node[title="${id}"]`);
const pathEdge = (page: Page, id: string) => page.locator(`.react-flow__edge-path.path-edge[id="${id}"]`);
const chip = (page: Page, id: string) => page.locator('.edge-chip', { has: page.locator(`button[title="${id}"]`) });

test('board shows inventory-service and its database', async ({ page }) => {
  await page.goto('/');
  await expect(node(page, 'inventory-service')).toBeVisible();
  await expect(node(page, 'postgresql-inventory')).toBeVisible();
});

test('out-of-stock path crosses both services and is marked failed', async ({ page }) => {
  await page.goto('/');
  await pickOutOfStock(page);

  for (const id of ['e2e-client--orders-service', 'orders-service--inventory-service', 'inventory-service--postgresql-inventory']) {
    await expect(pathEdge(page, id)).toHaveCount(1);
  }
  await expect(page.locator('.react-flow__edge-path.path-edge')).toHaveCount(3);
  await expect(chip(page, 'orders-service--inventory-service')).toHaveClass(/is-failed/);
});

test('sequence shows four participants and the 409', async ({ page }) => {
  await page.goto('/');
  await pickOutOfStock(page);
  await page.getByRole('button', { name: 'Open sequence' }).click();
  await expect(page).toHaveURL(/\/sequence\/run-4$/);

  const heads = page.locator('.seq-head'); // the head's title is the component id; participants come in first-seen order
  await expect(heads).toHaveCount(4);
  for (const [i, id] of ['e2e-client', 'orders-service', 'inventory-service', 'postgresql-inventory'].entries()) {
    await expect(heads.nth(i)).toHaveAttribute('title', id);
  }
  const diagram = page.getByRole('group', { name: 'Sequence diagram' });
  await expect(diagram.getByRole('button', { name: 'response 409' }).first()).toBeVisible();

  await diagram.getByRole('button', { name: 'request POST /reservations' }).click();
  const details = page.getByRole('complementary', { name: 'Message details' });
  await expect(details).toContainText('sku-out');
});
