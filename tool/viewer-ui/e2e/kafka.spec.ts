import { expect, test, type Page } from '@playwright/test';

// run-7 is a real acceptance recording of create-order, renamed create-order-shipping so the create-order specs keep
// their own latest run: orders-service produces to order-placed, and shipping-service consumes it
async function pickShippingScenario(page: Page) {
  await page.getByRole('button', { name: /scenario/i }).click();
  await page.getByRole('option', { name: /create-order-shipping/ }).click();
}

const node = (page: Page, id: string) => page.locator(`.component-node[title="${id}"]`);
const pathEdge = (page: Page, id: string) => page.locator(`.react-flow__edge-path.path-edge[id="${id}"]`);
const diagram = (page: Page) => page.getByRole('group', { name: 'Sequence diagram' });

test('board highlights the hop from orders-service through order-placed to shipping-service', async ({ page }) => {
  await page.goto('/');
  await pickShippingScenario(page);

  await expect(node(page, 'topic-order-placed')).toContainText('order-placed');
  for (const id of ['orders-service', 'topic-order-placed', 'shipping-service']) {
    await expect(node(page, id)).toHaveClass(/on-path/);
  }
  for (const id of ['orders-service--topic-order-placed', 'topic-order-placed--shipping-service']) {
    await expect(pathEdge(page, id)).toHaveCount(1);
  }
});

test('sequence shows the topic, the consume with its gap, and who produced it', async ({ page }) => {
  await page.goto('/');
  await pickShippingScenario(page);
  await page.getByRole('button', { name: 'Open sequence' }).click();
  await expect(page).toHaveURL(/\/sequence\/run-7$/);

  const topic = page.locator('.seq-head[title="topic-order-placed"]');
  await expect(topic).toContainText('order-placed');
  await expect(topic).toContainText('topic');

  await expect(diagram(page).getByRole('button', { name: 'request produce order-placed' })).toBeVisible();
  const consume = diagram(page).getByRole('button', { name: 'request consume order-placed' });
  await expect(consume.locator('.seq-note')).toHaveText(/^\+\d+ ms$/);

  await consume.click();
  const details = page.getByRole('complementary', { name: 'Message details' });
  await expect(details).toContainText('Produced by orders-service');
  await expect(details).toContainText(/Consumer group\s*shipping/);
});
