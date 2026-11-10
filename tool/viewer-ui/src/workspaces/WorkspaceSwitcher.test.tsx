import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { WorkspaceSwitcher } from './WorkspaceSwitcher';
import { entry, renderAt, stubWorkspaces } from './testing';

afterEach(() => vi.unstubAllGlobals());

function setup() {
  stubWorkspaces([entry('sam', 'Sam'), entry('other', 'Other'), entry('same', 'same')]);
  renderAt('/w/sam/', <WorkspaceSwitcher />);
}

test('the button is named after the current project and opens a menu of every workspace', async () => {
  const user = userEvent.setup();
  setup();
  const button = await screen.findByRole('button', { name: 'Sam' });
  expect(button).toHaveAttribute('aria-expanded', 'false');
  await user.click(button);
  expect(button).toHaveAttribute('aria-expanded', 'true');

  const items = within(screen.getByRole('menu')).getAllByRole('menuitemradio');
  expect(items).toHaveLength(3);
  expect(screen.getByRole('menuitemradio', { name: /^Sam/ })).toHaveAttribute('aria-checked', 'true');
  expect(screen.getByRole('menuitemradio', { name: /^Other/ })).toHaveAttribute('aria-checked', 'false');
  // the name shows beneath the project name only when they differ
  expect(screen.getByRole('menuitemradio', { name: /^Other/ })).toHaveTextContent('other');
  expect(screen.getByRole('menuitemradio', { name: /^same/ })).not.toHaveTextContent(/same.*same/);
  expect(screen.getByRole('menuitem', { name: 'Add workspace…' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: 'Manage…' })).toBeInTheDocument();
});

test('picking a workspace navigates to it and closes the menu', async () => {
  const user = userEvent.setup();
  setup();
  await user.click(await screen.findByRole('button', { name: 'Sam' }));
  await user.click(screen.getByRole('menuitemradio', { name: /^Other/ }));
  expect(screen.getByTestId('where')).toHaveTextContent('/w/other/');
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
});

test('Escape and an outside click close the menu', async () => {
  const user = userEvent.setup();
  setup();
  await user.click(await screen.findByRole('button', { name: 'Sam' }));
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Sam' }));
  await user.click(document.body);
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
});

test('Add workspace… and Manage… open their dialogs', async () => {
  const user = userEvent.setup();
  setup();
  await user.click(await screen.findByRole('button', { name: 'Sam' }));
  await user.click(screen.getByRole('menuitem', { name: 'Add workspace…' }));
  expect(screen.getByRole('dialog', { name: 'Add workspace' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Cancel' }));

  await user.click(screen.getByRole('button', { name: 'Sam' }));
  await user.click(screen.getByRole('menuitem', { name: 'Manage…' }));
  expect(screen.getByRole('dialog', { name: 'Manage workspaces' })).toBeInTheDocument();
});
