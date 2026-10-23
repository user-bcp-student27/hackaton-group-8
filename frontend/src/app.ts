import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DialogFocus } from './dialog-focus';
import { api, ApiFailure, cents, randomId } from './api';
import { AgentReply, Audit, Contact, Metrics, Outcome, Payment, PaymentInput, Qr, Reconciliation, SupportCase, User } from './models';

type View = 'home' | 'pay' | 'history' | 'merchant' | 'operations' | 'metrics';
interface Attempt { key: string; input: PaymentInput }

@Component({
  selector: 'tilin-app',
  standalone: true,
  imports: [CommonModule, FormsModule, DialogFocus],
  templateUrl: './app.html',
})
export class App implements OnInit, OnDestroy {
  user = signal<User | null>(null);
  ready = signal(false);
  laboratory = signal(false);
  busy = signal(false);
  error = signal('');
  notice = signal('');
  view = signal<View>('home');
  contacts = signal<Contact[]>([]);
  contactResults = signal<Contact[]>([]);
  contactsSearching = signal(false);
  selectedDestination = signal<Contact | null>(null);
  payments = signal<Payment[]>([]);
  selected = signal<Payment | null>(null);
  qr = signal<Qr | null>(null);
  scanned = signal<Qr | null>(null);
  reconciliation = signal<Reconciliation | null>(null);
  metrics = signal<Metrics | null>(null);
  cases = signal<SupportCase[]>([]);
  kycQueue = signal<{id: string; name: string; alias: string; status: string}[]>([]);
  audit = signal<Audit[]>([]);
  agent = signal<AgentReply | null>(null);
  assistantOpen = signal(false);
  assistantBusy = signal(false);
  registering = signal(false);
  step = signal(1);
  reviewing = signal(false);
  attempt = signal<Attempt | null>(null);
  destination = '';
  amount = '';
  channel: 'ALIAS' | 'QR' = 'ALIAS';
  qrAmount = '25.00';
  filterDate = '';
  filterReference = '';
  filterChannel = '';
  reconDate = this.today();
  reconChannel = '';
  month = this.today().slice(0, 7);
  campaign = 'organico';
  spend = '';
  name = ''; alias = ''; phone = ''; consent = false; pin = ''; confirmPin = '';
  caseCategory = 'TECHNICAL';
  caseNote = '';
  decisionNote = '';
  callbackOutcome: Outcome = 'APPROVED';
  fixtureOutcome: Outcome = 'DECLINED';
  fixtureAmount = '';
  question = '';
  returningAlias = '';
  returningPin = '';
  contactSearch = '';
  agentPaymentId = signal<string | null>(null);
  private poll?: ReturnType<typeof setInterval>;
  private contactSearchTimer?: ReturnType<typeof setTimeout>;
  private contactSearchSequence = 0;
  private reviewKey: string | null = null;

  private attemptStorageKeys(): string[] {
    const userId = this.user()?.id;
    return userId ? [`tilin-attempt-${userId}`, `inti-attempt-${userId}`] : [];
  }

  async ngOnInit(): Promise<void> {
    try {
      this.laboratory.set((await api<{laboratory: boolean}>('/experience')).laboratory);
      this.user.set(await api<User>('/me'));
      this.view.set(this.user()?.role === 'OPS' ? 'operations' : this.user()?.role === 'MERCHANT' ? 'merchant' : 'home');
      await this.loadData();
      if (this.user()?.completed_at === null) {
        this.registering.set(true);
        const user = this.user();
        this.step.set(user?.onboarding_step ?? 1);
        this.name = user?.name === 'Registro en progreso' ? '' : user?.name ?? '';
        this.alias = user?.alias.startsWith('draft-') ? '' : user?.alias ?? '';
        this.phone = user?.phone ?? '';
        this.consent = this.step() === 3;
      }
      this.restoreAttempt();
    } catch (error) {
      if (!(error instanceof ApiFailure && error.status === 401)) this.handle(error);
    } finally { this.ready.set(true); }
    this.poll = setInterval(() => {
      if (this.user() && !this.busy() && !document.hidden) void this.refreshQuietly();
    }, 5000);
  }
  ngOnDestroy(): void {
    if (this.poll) clearInterval(this.poll);
    if (this.contactSearchTimer) clearTimeout(this.contactSearchTimer);
  }
  today(): string { return new Intl.DateTimeFormat('en-CA', {timeZone: 'America/Lima', year: 'numeric', month: '2-digit', day: '2-digit'}).format(new Date()); }
  money(value: number | null | undefined): string { return value == null ? 'Sin datos' : new Intl.NumberFormat('es-PE', {style: 'currency', currency: 'PEN'}).format(value / 100); }
  date(value: string): string { return new Intl.DateTimeFormat('es-PE', {timeZone: 'America/Lima', dateStyle: 'medium', timeStyle: 'short'}).format(new Date(value)); }
  status(value: string): string {
    return ({ SUCCEEDED: 'Exitoso', FAILED: 'Rechazado', EXPIRED: 'Expirado', PENDING: 'Pendiente', ACTIVE: 'Activa', REJECTED: 'Rechazada', OPEN: 'Abierto', RESOLVED: 'Resuelto', INVESTIGATING: 'En investigación' } as Record<string, string>)[value] ?? value;
  }
  reason(value: string): string {
    return ({PROVIDER_APPROVED: 'Pago confirmado', PROVIDER_DECLINED: 'El proveedor no aceptó el pago', PROVIDER_TIMEOUT: 'Venció el plazo de confirmación', AWAITING_PROVIDER: 'Esperando confirmación'} as Record<string, string>)[value] ?? 'Consulta a soporte con tu referencia';
  }
  category(value: string): string {
    return ({TECHNICAL: 'Problema con el pago', DISPUTE: 'Revisión de pago', REVERSAL: 'Solicitud de devolución'} as Record<string, string>)[value] ?? value;
  }
  personName(): string { return this.user()?.name.split(' ')[0] ?? ''; }
  latest(): Payment[] { return this.payments().slice(0, 4); }
  paidTotal(): number { return this.payments().filter(p => p.payer_id === this.user()?.id && p.status === 'SUCCEEDED' && !p.reversed).reduce((sum, p) => sum + p.amount, 0); }
  receivedTotal(): number { return this.payments().filter(p => p.destination_id === this.user()?.id && p.status === 'SUCCEEDED' && !p.reversed).reduce((sum, p) => sum + p.amount, 0); }
  handle(error: unknown): void {
    this.error.set(error instanceof Error ? error.message : 'Ocurrió un error inesperado.');
    if (error instanceof ApiFailure && error.status === 401) {
      this.user.set(null); this.selected.set(null); this.attempt.set(null); this.registering.set(false);
      this.payments.set([]); this.contacts.set([]); this.contactResults.set([]); this.qr.set(null); this.scanned.set(null); this.agent.set(null);
      this.cases.set([]); this.audit.set([]); this.metrics.set(null); this.reconciliation.set(null); this.kycQueue.set([]);
    }
  }
  async run(work: () => Promise<void>): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true); this.error.set(''); this.notice.set('');
    try { await work(); } catch (error) { this.handle(error); } finally { this.busy.set(false); }
  }
  async loadData(): Promise<void> {
    const user = this.user();
    if (!user || user.pin_setup_required) return;
    if (user.status === 'ACTIVE') {
      const [history, contacts] = await Promise.all([
        api<Payment[]>(`/payments?date=${encodeURIComponent(this.filterDate)}&reference=${encodeURIComponent(this.filterReference)}&channel=${this.filterChannel}`),
        user.role === 'OPS' ? Promise.resolve([]) : api<Contact[]>('/contacts'),
      ]);
      if (this.user()?.id !== user.id) return;
      this.payments.set(history); this.contacts.set(contacts);
      if (!this.contactSearch.trim()) this.contactResults.set(contacts);
    }
    if (user.role === 'OPS') {
      const [cases, queue, audit] = await Promise.all([
        api<SupportCase[]>('/ops/cases'), api<{id: string; name: string; alias: string; status: string}[]>('/ops/onboarding'), api<Audit[]>('/ops/audit'),
      ]);
      if (this.user()?.id !== user.id) return;
      this.cases.set(cases); this.kycQueue.set(queue); this.audit.set(audit);
    }
  }
  async refreshQuietly(): Promise<void> {
    const uid = this.user()?.id;
    try {
      const user = await api<User>('/me');
      if (this.user()?.id !== uid) return;
      this.user.set(user);
      await this.loadData();
      const selected = this.selected();
      if (selected) {
        const payment = await api<Payment>(`/payments/${selected.id}`);
        if (this.user()?.id === uid && this.selected()?.id === selected.id) this.selected.set(payment);
      }
    } catch (error) { if (this.user()?.id === uid) this.handle(error); }
  }
  async login(profile: string, pin?: string): Promise<void> {
    await this.run(async () => {
      await api('/demo/login', { userId: profile, ...(pin ? {pin} : {}) });
      this.user.set(await api<User>('/me'));
      this.view.set(this.user()?.role === 'MERCHANT' ? 'merchant' : this.user()?.role === 'OPS' ? 'operations' : 'home');
      this.selected.set(null); this.attempt.set(null); this.agent.set(null); this.registering.set(false); this.reviewing.set(false); this.qr.set(null); this.scanned.set(null);
      this.agentPaymentId.set(null);
      await this.loadData();
      this.restoreAttempt();
    });
  }
  async logout(): Promise<void> {
    await this.run(async () => {
      await api('/logout', {});
      this.user.set(null); this.payments.set([]); this.contacts.set([]); this.contactResults.set([]); this.selected.set(null); this.selectedDestination.set(null); this.attempt.set(null);
      this.qr.set(null); this.scanned.set(null); this.agent.set(null); this.registering.set(false); this.reviewing.set(false);
      this.agentPaymentId.set(null);
      this.cases.set([]); this.audit.set([]); this.metrics.set(null); this.reconciliation.set(null); this.kycQueue.set([]);
      this.filterDate = ''; this.filterReference = ''; this.filterChannel = '';
    });
  }
  async loginAlias(): Promise<void> {
    const pin = this.returningPin;
    await this.login(this.returningAlias.trim(), pin);
    this.returningPin = '';
  }
  async loginLegacyAlias(): Promise<void> {
    await this.login(this.returningAlias.trim());
    this.returningPin = '';
  }
  async setupLegacyPin(): Promise<void> {
    if (!/^[0-9]{6}$/.test(this.pin)) { this.error.set('Crea una clave de exactamente 6 dígitos.'); return; }
    if (this.pin !== this.confirmPin) { this.error.set('Las claves no coinciden.'); return; }
    await this.run(async () => {
      this.user.set(await api<User>('/profile/pin', {pin: this.pin}));
      this.pin = ''; this.confirmPin = '';
      await this.loadData();
      this.notice.set('Tu clave quedó configurada. En adelante ingresa con tu alias y clave.');
    });
  }
  async startOnboarding(): Promise<void> {
    await this.run(async () => {
      await api('/onboarding/start', {campaign: 'organico'});
      this.user.set(await api<User>('/me')); this.registering.set(true); this.step.set(1);
      this.pin = ''; this.confirmPin = '';
    });
  }
  previousStep(): void { this.step.update(previous => previous - 1); }
  async nextStep(): Promise<void> {
    this.error.set('');
    if (this.step() === 1) {
      if (this.name.trim().length < 3 || this.name.trim().length > 80 || !/^[a-z][a-z0-9.]{2,29}$/.test(this.alias) || !/^9\d{8}$/.test(this.phone)) {
        this.error.set('Revisa tu nombre ficticio, alias (3–30 caracteres, minúsculas) y celular peruano (9 dígitos).'); return;
      }
    } else if (!this.consent) { this.error.set('Acepta el uso exclusivo de datos sintéticos.'); return; }
    await this.run(async () => {
      this.user.set(await api<User>('/onboarding/step', {step: this.step() === 1 ? 'identity' : 'consent',
        name: this.name, alias: this.alias, phone: this.phone, consent: this.consent}));
      this.step.update(s => Math.min(3, s + 1));
    });
  }
  async finishOnboarding(): Promise<void> {
    if (!/^[0-9]{6}$/.test(this.pin)) { this.error.set('Crea una clave de exactamente 6 dígitos.'); return; }
    if (this.pin !== this.confirmPin) { this.error.set('Las claves no coinciden.'); return; }
    await this.run(async () => {
      this.user.set(await api<User>('/onboarding/complete', {name: this.name, alias: this.alias, phone: this.phone, consent: this.consent, pin: this.pin}));
      this.pin = ''; this.confirmPin = '';
      this.registering.set(false); this.view.set('home'); await this.loadData();
      this.notice.set(this.user()?.status === 'ACTIVE' ? 'Tu wallet está lista. Tienes S/ 500 de saldo de prueba para empezar.' : 'Estamos revisando tu cuenta. Puedes consultar el estado aquí.');
    });
  }
  navigate(view: View): void { this.view.set(view); this.error.set(''); this.notice.set(''); }
  preparePay(contact?: Contact): void {
    if (this.attempt()) { this.error.set('Primero consulta o cierra el intento anterior desde el formulario de pago.'); this.view.set('pay'); return; }
    this.destination = contact?.alias ?? ''; this.amount = ''; this.channel = 'ALIAS';
    this.selectedDestination.set(contact ?? null);
    this.contactSearch = ''; this.contactResults.set(this.contacts());
    this.reviewKey = null;
    this.scanned.set(null); this.reviewing.set(false); this.navigate('pay');
  }
  searchContacts(): void {
    const query = this.contactSearch.trim();
    const sequence = ++this.contactSearchSequence;
    if (this.contactSearchTimer) clearTimeout(this.contactSearchTimer);
    if (!query) {
      this.contactsSearching.set(false);
      this.contactResults.set(this.contacts());
      return;
    }
    this.contactSearchTimer = setTimeout(async () => {
      this.contactsSearching.set(true);
      try {
        const matches = await api<Contact[]>(`/contacts?q=${encodeURIComponent(query)}`);
        if (sequence === this.contactSearchSequence) this.contactResults.set(matches);
      } catch (error) {
        if (sequence === this.contactSearchSequence) this.handle(error);
      } finally {
        if (sequence === this.contactSearchSequence) this.contactsSearching.set(false);
      }
    }, 250);
  }
  selectDestination(alias: string): void {
    this.destination = alias;
    const contact = this.contactResults().find(item => item.alias === alias) ?? this.contacts().find(item => item.alias === alias);
    this.selectedDestination.set(contact ?? null);
  }
  async reviewPayment(): Promise<void> {
    this.error.set('');
    try {
      if (!this.contacts().some(c => c.alias === this.destination) && !this.contactResults().some(c => c.alias === this.destination)) throw new Error('Selecciona un destino antes de continuar.');
      cents(this.amount, this.channel === 'QR' ? 15000 : 20000);
      if (cents(this.amount, this.channel === 'QR' ? 15000 : 20000) > (this.user()?.balance ?? 0)) throw new Error('Tu saldo disponible no alcanza. Ingresa un monto menor.');
      if (this.channel === 'QR' && !this.scanned()) throw new Error('Selecciona primero la imagen del QR del comercio.');
      await this.run(async () => {
        this.reviewKey ??= randomId();
        await api('/checkout/start', {attemptId: this.reviewKey});
        this.reviewing.set(true);
      });
    } catch (error) { this.handle(error); }
  }
  destinationContact(): Contact | undefined {
    return this.selectedDestination()?.alias === this.destination ? this.selectedDestination() ?? undefined
      : this.contactResults().find(contact => contact.alias === this.destination) ?? this.contacts().find(contact => contact.alias === this.destination);
  }
  async confirmPayment(): Promise<void> {
    await this.run(async () => {
      let attempt = this.attempt();
      if (!attempt) {
        attempt = {key: this.reviewKey ?? randomId(), input: {destination: this.destination, amount: cents(this.amount, this.channel === 'QR' ? 15000 : 20000),
          channel: this.channel, qrId: this.scanned()?.id ?? null}};
        this.attempt.set(attempt);
        const [storageKey] = this.attemptStorageKeys();
        if (storageKey) sessionStorage.setItem(storageKey, JSON.stringify(attempt));
      }
      let result: Payment;
      try {
        result = await api<Payment>('/payments', attempt.input, {'Idempotency-Key': attempt.key});
      } catch (error) {
        if (error instanceof ApiFailure && [400, 403, 413, 422].includes(error.status)) {
          for (const storageKey of this.attemptStorageKeys()) sessionStorage.removeItem(storageKey);
          this.attempt.set(null);
          this.reviewing.set(false);
        }
        throw error;
      }
      this.selected.set(await api<Payment>(`/payments/${result.id}`));
      this.reviewing.set(false); this.user.set(await api<User>('/me')); await this.loadData();
      this.notice.set(result.duplicate ? 'Recuperamos tu pago. No se realizó un segundo cobro.' : result.status === 'SUCCEEDED' ? 'Tu pago se realizó correctamente.' : result.status === 'PENDING' ? 'Estamos confirmando tu pago. No vuelvas a enviarlo.' : 'El pago no se realizó. Tu saldo está disponible.');
    });
  }
  restoreAttempt(): void {
    const keys = this.attemptStorageKeys();
    const saved = keys.map(key => sessionStorage.getItem(key)).find(value => value !== null);
    if (!saved || this.attempt()) return;
    try {
      const value: unknown = JSON.parse(saved);
      if (typeof value === 'object' && value !== null && 'key' in value && typeof value.key === 'string' && 'input' in value
          && typeof value.input === 'object' && value.input !== null && 'amount' in value.input && typeof value.input.amount === 'number'
          && 'destination' in value.input && typeof value.input.destination === 'string'
          && 'channel' in value.input && (value.input.channel === 'ALIAS' || value.input.channel === 'QR')
          && (!('scenario' in value.input) || (typeof value.input.scenario === 'string' && ['APPROVED', 'DECLINED', 'PENDING', 'TIMEOUT'].includes(value.input.scenario)))
          && 'qrId' in value.input && (value.input.qrId === null || typeof value.input.qrId === 'string')) {
        this.attempt.set({key: value.key, input: {amount: value.input.amount, destination: value.input.destination, channel: value.input.channel,
          ...('scenario' in value.input ? {scenario: value.input.scenario as Outcome} : {}), qrId: value.input.qrId}});
        if (keys.length && sessionStorage.getItem(keys[0]) === null) sessionStorage.setItem(keys[0], saved);
        this.view.set('pay');
        this.notice.set('Tienes un pago por consultar. Recupera su resultado antes de enviar otro.');
      } else throw new Error('El intento guardado tiene formato inválido. Contacta a operaciones antes de volver a pagar.');
    } catch (error) { this.handle(error); }
  }
  async closeAttempt(next: 'home' | 'pay' = 'pay'): Promise<void> {
    await this.run(async () => {
      const attempt = this.attempt();
      if (!attempt) return;
      const result = await api<Payment>('/payments', attempt.input, {'Idempotency-Key': attempt.key});
      if (result.status === 'PENDING') { this.selected.set(await api<Payment>(`/payments/${result.id}`)); throw new Error('Tu pago sigue en proceso. Consulta su estado o solicita ayuda; no lo envíes de nuevo.'); }
      for (const storageKey of this.attemptStorageKeys()) sessionStorage.removeItem(storageKey);
      this.attempt.set(null); this.reviewing.set(false); this.amount = ''; this.scanned.set(null); this.channel = 'ALIAS';
      this.reviewKey = null;
      this.selected.set(null); this.view.set(next);
      this.user.set(await api<User>('/me')); await this.loadData();
      this.notice.set('Puedes consultar tu comprobante en Movimientos.');
    });
  }
  async finishPayment(next: 'home' | 'pay'): Promise<void> {
    const payment = this.selected();
    if (this.attempt() && payment?.payer_id === this.user()?.id) await this.closeAttempt(next);
    else { this.selected.set(null); if (next === 'pay') this.preparePay(); else this.navigate('home'); }
  }
  async openPayment(payment: Payment): Promise<void> {
    await this.run(async () => { this.selected.set(await api<Payment>(`/payments/${payment.id}`)); this.caseNote = ''; this.caseCategory = payment.status === 'SUCCEEDED' ? 'DISPUTE' : 'TECHNICAL'; });
  }
  async makeQr(): Promise<void> {
    await this.run(async () => { this.qr.set(await api<Qr>('/qr', {amount: cents(this.qrAmount, 15000)})); });
  }
  async uploadQr(event: Event): Promise<void> {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    await this.run(async () => {
      if (this.attempt()) throw new Error('Consulta y cierra el intento anterior antes de leer otro QR.');
      if (file.type !== 'image/png' || file.size > 1000000) throw new Error('Selecciona un archivo PNG de hasta 1 MB.');
      const image = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => typeof reader.result === 'string' ? resolve(reader.result) : reject(new Error('No se pudo leer el PNG.'));
        reader.onerror = () => reject(new Error('No se pudo abrir el archivo.'));
        reader.readAsDataURL(file);
      });
      const qr = await api<Qr>('/qr/decode', {image});
      this.reviewKey = null;
      this.scanned.set(qr); this.destination = qr.alias;
      this.selectedDestination.set({id: qr.merchant_id, name: qr.name, alias: qr.alias, role: 'MERCHANT'});
      this.contactSearch = ''; this.contactResults.set(this.contacts());
      this.amount = (qr.amount / 100).toFixed(2); this.channel = 'QR'; this.view.set('pay'); this.reviewing.set(false);
      this.notice.set('QR leído. Verifica el comercio y el monto antes de confirmar.');
    });
    input.value = '';
  }
  async filterHistory(): Promise<void> { await this.run(async () => this.loadData()); }
  async createCase(): Promise<void> {
    const payment = this.selected();
    if (!payment) return;
    await this.run(async () => {
      await api(`/payments/${payment.id}/cases`, {category: this.caseCategory, note: this.caseNote});
      this.selected.set(await api<Payment>(`/payments/${payment.id}`)); this.caseNote = '';
      this.notice.set('Solicitud enviada a revisión humana. No se ha movido dinero.'); await this.loadData();
    });
  }
  async callback(): Promise<void> {
    const p = this.selected(); if (!p) return;
    await this.run(async () => {
      await api(`/ops/payments/${p.id}/callback`, {eventId: randomId(), outcome: this.callbackOutcome});
      this.selected.set(await api<Payment>(`/payments/${p.id}`)); await this.loadData(); this.notice.set('Callback mock aplicado.');
    });
  }
  async injectFixture(missing = false, repair = false): Promise<void> {
    const p = this.selected(); if (!p) return;
    await this.run(async () => {
      const outcomes: Record<string, Outcome> = {SUCCEEDED: 'APPROVED', FAILED: 'DECLINED', EXPIRED: 'TIMEOUT', PENDING: 'PENDING'};
      await api(`/ops/payments/${p.id}/fixture`, {missing, outcome: repair ? outcomes[p.status] : this.fixtureOutcome,
        amount: repair ? p.amount : missing ? null : cents(this.fixtureAmount, 20000)});
      this.notice.set(repair ? 'Fuente del proveedor restaurada. Ejecuta nuevamente la conciliación.' : 'Fixture aplicado solo al proveedor. El ledger no se modificó.');
    });
  }
  async reconcile(): Promise<void> {
    await this.run(async () => { this.reconciliation.set(await api<Reconciliation>('/ops/reconciliation', {date: this.reconDate, channel: this.reconChannel})); await this.loadData(); });
  }
  async investigate(id: string): Promise<void> {
    await this.run(async () => {
      await api(`/ops/mismatches/${id}/investigate`, {});
      this.reconciliation.set(await api<Reconciliation>('/ops/reconciliation', {date: this.reconDate, channel: this.reconChannel}));
    });
  }
  async decide(support: SupportCase, decision: string): Promise<void> {
    await this.run(async () => {
      await api(`/ops/cases/${support.id}`, {decision, note: this.decisionNote});
      this.decisionNote = ''; await this.loadData(); this.notice.set('Decisión humana registrada en auditoría.');
    });
  }
  async reviewKyc(id: string, decision: string): Promise<void> {
    await this.run(async () => { await api(`/ops/onboarding/${id}`, {decision}); await this.loadData(); this.notice.set('Revisión KYC mock registrada.'); });
  }
  async loadMetrics(): Promise<void> {
    await this.run(async () => { this.metrics.set(await api<Metrics>(`/ops/metrics?launchMonth=${this.month}`)); this.view.set('metrics'); });
  }
  async saveSpend(): Promise<void> {
    await this.run(async () => {
      await api('/ops/acquisition', {month: this.month, campaign: this.campaign, cents: this.spend === '0' ? 0 : cents(this.spend, 100000000)});
      this.metrics.set(await api<Metrics>(`/ops/metrics?launchMonth=${this.month}`)); this.notice.set('Gasto de adquisición guardado por mes y campaña.');
    });
  }
  async askAgent(prompt?: string): Promise<void> {
    if (this.assistantBusy()) return;
    if (this.selected()) { this.agentPaymentId.set(this.selected()!.id); this.selected.set(null); }
    this.assistantOpen.set(true); this.assistantBusy.set(true); this.error.set('');
    this.agent.set({
      message: 'No puedo ayudarte ahora, estoy ocupado :D',
      intent: 'BUSY', trace_id: 'local', mode: 'LOCAL', human_review_required: false,
    });
    this.question = '';
    this.assistantBusy.set(false);
  }
  downloadReceipt(): void {
    const p = this.selected(); if (!p) return;
    const content = `TILIN - COMPROBANTE DE PRUEBA\nSIN VALIDEZ FINANCIERA\nReferencia: ${p.id}\nEstado: ${this.status(p.status)}\nPagador: ${p.payer_name}\nDestino: ${p.destination_name}\nMonto: ${this.money(p.amount)}\nCanal: ${p.channel}\nFecha Peru: ${this.date(p.created_at)}\nReverso simulado: ${p.reversed ? 'Si' : 'No'}\n`;
    const url = URL.createObjectURL(new Blob([content], {type: 'text/plain;charset=utf-8'}));
    const a = document.createElement('a'); a.href = url; a.download = `tilin-${p.id}.txt`; a.click(); URL.revokeObjectURL(url);
  }
}
