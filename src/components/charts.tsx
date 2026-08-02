// Graphiques du tableau de bord. Chaque graphique fournit une alternative
// tabulaire (RGAA) et suit la palette catégorielle validée (ordre fixe).

import type { CSSProperties } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { CHART_INK, slotColor, STATUS } from '../theme.ts'
import { formatEUR, formatEURCompact } from '../domain/money.ts'
import type { CategorySlice, BudgetStatus, PeriodSeriesPoint } from '../domain/stats.ts'
import type { FundingResult } from '../domain/funding.ts'

type Mode = 'light' | 'dark'

function tooltipStyle(mode: Mode): CSSProperties {
  return {
    background: CHART_INK[mode].surface,
    border: '1px solid var(--border)',
    borderRadius: 10,
    color: CHART_INK[mode].text,
    fontSize: '0.85rem',
  }
}

const MAX_SLICES = 7

export interface DonutSlice {
  name: string
  amount: number
  color: string
}

/** Regroupe au-delà de 7 catégories dans « Autres » (jamais de 9e teinte). */
export function toDonutSlices(slices: CategorySlice[], mode: Mode): DonutSlice[] {
  const top = slices.slice(0, MAX_SLICES).map((s) => ({
    name: s.category.name,
    amount: s.amount,
    color: slotColor(s.category.colorSlot, mode),
  }))
  const rest = slices.slice(MAX_SLICES)
  if (rest.length > 0) {
    top.push({
      name: 'Autres',
      amount: rest.reduce((sum, s) => sum + s.amount, 0),
      color: CHART_INK[mode].muted,
    })
  }
  return top
}

export function CategoryDonut({ slices, mode }: { slices: CategorySlice[]; mode: Mode }) {
  const donut = toDonutSlices(slices, mode)
  const total = donut.reduce((sum, s) => sum + s.amount, 0)
  if (total === 0) {
    return <p className="empty-state">Aucune dépense sur la période.</p>
  }
  return (
    <>
      <div role="img" aria-label={`Répartition des dépenses : ${donut.map((s) => `${s.name} ${formatEUR(s.amount)}`).join(', ')}`}>
        <ResponsiveContainer width="100%" height={220}>
          <PieChart>
            <Pie
              data={donut}
              dataKey="amount"
              nameKey="name"
              innerRadius="62%"
              outerRadius="92%"
              paddingAngle={2}
              strokeWidth={0}
              isAnimationActive={false}
            >
              {donut.map((s) => (
                <Cell key={s.name} fill={s.color} />
              ))}
            </Pie>
            <Tooltip
              formatter={(value) => formatEUR(Number(value))}
              contentStyle={tooltipStyle(mode)}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul className="legend">
        {donut.map((s) => (
          <li key={s.name}>
            <span className="swatch" style={{ background: s.color }} aria-hidden="true" />
            {s.name} — <strong>{formatEURCompact(s.amount)}</strong> (
            {Math.round((s.amount / total) * 100)} %)
          </li>
        ))}
      </ul>
      <details className="data-table">
        <summary>Voir les données en tableau</summary>
        <div className="table-wrap">
          <table className="data">
            <caption className="visually-hidden">Dépenses par catégorie</caption>
            <thead>
              <tr>
                <th scope="col">Catégorie</th>
                <th scope="col" className="num">
                  Montant
                </th>
                <th scope="col" className="num">
                  Part
                </th>
              </tr>
            </thead>
            <tbody>
              {donut.map((s) => (
                <tr key={s.name}>
                  <th scope="row">{s.name}</th>
                  <td className="num">{formatEUR(s.amount)}</td>
                  <td className="num">{Math.round((s.amount / total) * 100)} %</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  )
}

export function MonthlyBars({ series, mode }: { series: PeriodSeriesPoint[]; mode: Mode }) {
  const ink = CHART_INK[mode]
  const expenseColor = slotColor(1, mode)
  const incomeColor = slotColor(2, mode)
  const data = series.map((p) => ({
    label: p.period.shortLabel,
    fullLabel: p.period.label,
    Dépenses: p.expense / 100,
    Revenus: p.income / 100,
  }))
  return (
    <>
      <div role="img" aria-label="Évolution des dépenses et revenus par mois (tableau détaillé disponible ci-dessous)">
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={data} barGap={2} margin={{ top: 8, right: 8, left: 8, bottom: 0 }}>
            <CartesianGrid vertical={false} stroke={ink.grid} />
            <XAxis
              dataKey="label"
              tick={{ fill: ink.muted, fontSize: 12 }}
              axisLine={{ stroke: ink.axis }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: ink.muted, fontSize: 12 }}
              tickFormatter={(v: number) => formatEURCompact(v * 100)}
              axisLine={false}
              tickLine={false}
              width={64}
            />
            <Tooltip
              formatter={(value) => formatEUR(Math.round(Number(value) * 100))}
              labelFormatter={(_, payload) => payload?.[0]?.payload.fullLabel ?? ''}
              contentStyle={tooltipStyle(mode)}
              cursor={{ fill: ink.grid, opacity: 0.4 }}
            />
            <Bar dataKey="Dépenses" fill={expenseColor} radius={[4, 4, 0, 0]} isAnimationActive={false} />
            <Bar dataKey="Revenus" fill={incomeColor} radius={[4, 4, 0, 0]} isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <ul className="legend">
        <li>
          <span className="swatch" style={{ background: expenseColor }} aria-hidden="true" />
          Dépenses
        </li>
        <li>
          <span className="swatch" style={{ background: incomeColor }} aria-hidden="true" />
          Revenus
        </li>
      </ul>
      <details className="data-table">
        <summary>Voir les données en tableau</summary>
        <div className="table-wrap">
          <table className="data">
            <caption className="visually-hidden">Dépenses et revenus par mois</caption>
            <thead>
              <tr>
                <th scope="col">Mois</th>
                <th scope="col" className="num">
                  Dépenses
                </th>
                <th scope="col" className="num">
                  Revenus
                </th>
              </tr>
            </thead>
            <tbody>
              {series.map((p) => (
                <tr key={p.period.key}>
                  <th scope="row">{p.period.label}</th>
                  <td className="num">{formatEUR(p.expense)}</td>
                  <td className="num">{formatEUR(p.income)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  )
}

export function BudgetVsActual({ statuses, mode }: { statuses: BudgetStatus[]; mode: Mode }) {
  const ink = CHART_INK[mode]
  const budgetColor = ink.axis
  const spentColor = slotColor(1, mode)
  if (statuses.length === 0) {
    return (
      <p className="empty-state">
        Définis des budgets pour comparer prévu et réel.
      </p>
    )
  }
  const data = statuses.map((s) => ({
    name: `${s.category.icon} ${s.category.name}`,
    plainName: s.category.name,
    Budget: s.budget.monthlyAmount / 100,
    Dépensé: s.spent / 100,
  }))
  const height = Math.max(160, data.length * 56)
  return (
    <>
      <div role="img" aria-label="Comparaison budget prévu et dépensé par catégorie (tableau détaillé disponible ci-dessous)">
        <ResponsiveContainer width="100%" height={height}>
          <BarChart data={data} layout="vertical" barGap={2} margin={{ top: 0, right: 16, left: 8, bottom: 0 }}>
            <CartesianGrid horizontal={false} stroke={ink.grid} />
            <XAxis
              type="number"
              tick={{ fill: ink.muted, fontSize: 12 }}
              tickFormatter={(v: number) => formatEURCompact(v * 100)}
              axisLine={{ stroke: ink.axis }}
              tickLine={false}
            />
            <YAxis
              type="category"
              dataKey="name"
              width={120}
              tick={{ fill: ink.text, fontSize: 12 }}
              axisLine={false}
              tickLine={false}
            />
            <Tooltip
              formatter={(value) => formatEUR(Math.round(Number(value) * 100))}
              contentStyle={tooltipStyle(mode)}
              cursor={{ fill: ink.grid, opacity: 0.4 }}
            />
            <Bar dataKey="Budget" fill={budgetColor} radius={[0, 4, 4, 0]} isAnimationActive={false} barSize={10} />
            <Bar dataKey="Dépensé" fill={spentColor} radius={[0, 4, 4, 0]} isAnimationActive={false} barSize={10} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <ul className="legend">
        <li>
          <span className="swatch" style={{ background: budgetColor }} aria-hidden="true" />
          Budget prévu
        </li>
        <li>
          <span className="swatch" style={{ background: spentColor }} aria-hidden="true" />
          Dépensé
        </li>
      </ul>
      <details className="data-table">
        <summary>Voir les données en tableau</summary>
        <div className="table-wrap">
          <table className="data">
            <caption className="visually-hidden">Budget prévu et dépensé par catégorie</caption>
            <thead>
              <tr>
                <th scope="col">Catégorie</th>
                <th scope="col" className="num">
                  Budget
                </th>
                <th scope="col" className="num">
                  Dépensé
                </th>
              </tr>
            </thead>
            <tbody>
              {statuses.map((s) => (
                <tr key={s.budget.id}>
                  <th scope="row">{s.category.name}</th>
                  <td className="num">{formatEUR(s.budget.monthlyAmount)}</td>
                  <td className="num">{formatEUR(s.spent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  )
}

export function FundingChart({ result, mode }: { result: FundingResult; mode: Mode }) {
  const ink = CHART_INK[mode]
  const fixedColor = slotColor(1, mode)
  const variableColor = slotColor(2, mode)
  const hasVariable = result.totalVariableIncome > 0
  const data = result.timeline.map((p) => ({
    label: p.label,
    'Solde projeté': p.projectedFixed / 100,
    'Avec revenus variables': p.projectedWithVariable / 100,
  }))
  const target = result.targetAmount / 100
  return (
    <>
      <div
        role="img"
        aria-label={`Trajectoire du solde mobilisable jusqu'à l'échéance, objectif ${formatEUR(result.targetAmount)} (tableau détaillé ci-dessous)`}
      >
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={data} margin={{ top: 8, right: 12, left: 8, bottom: 0 }}>
            <CartesianGrid vertical={false} stroke={ink.grid} />
            <XAxis
              dataKey="label"
              tick={{ fill: ink.muted, fontSize: 12 }}
              axisLine={{ stroke: ink.axis }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: ink.muted, fontSize: 12 }}
              tickFormatter={(v: number) => formatEURCompact(v * 100)}
              axisLine={false}
              tickLine={false}
              width={64}
            />
            <Tooltip
              formatter={(value) => formatEUR(Math.round(Number(value) * 100))}
              contentStyle={tooltipStyle(mode)}
            />
            <ReferenceLine
              y={target}
              stroke={STATUS.critical}
              strokeDasharray="5 4"
              label={{ value: 'Objectif', position: 'insideTopRight', fill: ink.muted, fontSize: 11 }}
            />
            {hasVariable && (
              <Line
                type="monotone"
                dataKey="Avec revenus variables"
                stroke={variableColor}
                strokeWidth={2}
                strokeDasharray="4 3"
                dot={false}
                isAnimationActive={false}
              />
            )}
            <Line
              type="monotone"
              dataKey="Solde projeté"
              stroke={fixedColor}
              strokeWidth={2}
              dot={{ r: 3, fill: fixedColor }}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <ul className="legend">
        <li>
          <span className="swatch" style={{ background: fixedColor }} aria-hidden="true" />
          Solde projeté (revenus fixes)
        </li>
        {hasVariable && (
          <li>
            <span className="swatch" style={{ background: variableColor }} aria-hidden="true" />
            Avec revenus variables
          </li>
        )}
        <li>
          <span className="swatch" style={{ background: STATUS.critical }} aria-hidden="true" />
          Objectif à atteindre
        </li>
      </ul>
      <details className="data-table">
        <summary>Voir les données en tableau</summary>
        <div className="table-wrap">
          <table className="data">
            <caption className="visually-hidden">Solde projeté par mois</caption>
            <thead>
              <tr>
                <th scope="col">Mois</th>
                <th scope="col" className="num">Revenus</th>
                <th scope="col" className="num">Dépenses</th>
                <th scope="col" className="num">Solde projeté</th>
              </tr>
            </thead>
            <tbody>
              {result.timeline.map((p) => (
                <tr key={p.monthKey}>
                  <th scope="row">{p.label}</th>
                  <td className="num">{formatEUR(p.fixedIncome + p.variableIncome)}</td>
                  <td className="num">{formatEUR(p.expenseEvents)}</td>
                  <td className="num">{formatEUR(p.projectedFixed)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  )
}
