// Données créées à la première utilisation : un compte courant et un jeu de
// catégories prédéfinies (modifiables ensuite par l'utilisateur).

import { stamp, type Account, type Category } from './types.ts'

export function defaultAccount(): Account {
  return {
    ...stamp(),
    name: 'Compte courant',
    type: 'checking',
    initialBalance: 0,
    icon: '🏦',
    archived: false,
  }
}

interface CategorySeed {
  name: string
  icon: string
  kind: 'expense' | 'income'
  colorSlot: number
}

// Les emplacements couleur suivent l'ordre fixe de la palette catégorielle.
const SEEDS: CategorySeed[] = [
  { name: 'Alimentation', icon: '🛒', kind: 'expense', colorSlot: 1 },
  { name: 'Logement', icon: '🏠', kind: 'expense', colorSlot: 2 },
  { name: 'Transport', icon: '🚗', kind: 'expense', colorSlot: 3 },
  { name: 'Loisirs', icon: '🎮', kind: 'expense', colorSlot: 4 },
  { name: 'Santé', icon: '💊', kind: 'expense', colorSlot: 5 },
  { name: 'Abonnements', icon: '📱', kind: 'expense', colorSlot: 6 },
  { name: 'Restaurants', icon: '🍽️', kind: 'expense', colorSlot: 7 },
  { name: 'Vêtements', icon: '👕', kind: 'expense', colorSlot: 8 },
  { name: 'Divers', icon: '📦', kind: 'expense', colorSlot: 1 },
  { name: 'Salaire', icon: '💼', kind: 'income', colorSlot: 2 },
  { name: 'Autres revenus', icon: '💶', kind: 'income', colorSlot: 5 },
]

export function defaultCategories(): Category[] {
  return SEEDS.map((seed) => ({ ...stamp(), ...seed, parentId: null }))
}
