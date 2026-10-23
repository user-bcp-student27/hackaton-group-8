export class ApiFailure extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

export async function api<T>(path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Wallet-Request': '1', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
  } catch (error) {
    console.error('Wallet network request failed', error);
    throw new ApiFailure(0, 'NETWORK', 'No se pudo conectar. Si estabas pagando, usa “Consultar mismo intento” antes de iniciar otro.');
  }
  const payload: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const valid = payload !== null && typeof payload === 'object';
    const message = valid && 'message' in payload && typeof payload.message === 'string' ? payload.message : 'El servidor no pudo completar la solicitud.';
    const code = valid && 'code' in payload && typeof payload.code === 'string' ? payload.code : 'HTTP_ERROR';
    throw new ApiFailure(response.status, code, message);
  }
  if (payload === null) throw new ApiFailure(502, 'INVALID_RESPONSE', 'El servidor devolvió una respuesta no válida.');
  return payload as T;
}

export function cents(value: string, maximum: number): number {
  if (!/^\d{1,7}([.,]\d{1,2})?$/.test(value)) throw new Error('Ingresa un monto con hasta dos decimales.');
  const [whole, fraction = ''] = value.replace(',', '.').split('.');
  const result = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(result) || result <= 0 || result > maximum) throw new Error(`El monto debe ser mayor a cero y no superar S/ ${(maximum / 100).toFixed(2)}.`);
  return result;
}
