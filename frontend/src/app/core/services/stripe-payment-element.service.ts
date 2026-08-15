import { Injectable } from '@angular/core';

interface StripePaymentElement {
  mount(selector: string): void;
  unmount(): void;
}

interface StripeElements {
  create(type: 'payment'): StripePaymentElement;
}

interface StripeConfirmationResult {
  error?: { message?: string };
  paymentIntent?: { status: string };
}

interface StripeClient {
  elements(options: { clientSecret: string }): StripeElements;
  confirmPayment(options: {
    elements: StripeElements;
    confirmParams: { return_url: string };
    redirect: 'if_required';
  }): Promise<StripeConfirmationResult>;
}

type StripeFactory = (publishableKey: string) => StripeClient;

@Injectable({ providedIn: 'root' })
export class StripePaymentElementService {
  private script?: Promise<void>;
  private client?: StripeClient;
  private elements?: StripeElements;
  private paymentElement?: StripePaymentElement;
  private returnUrl?: string;

  async mount(
    publishableKey: string,
    clientSecret: string,
    returnUrl: string,
    selector: string,
  ): Promise<void> {
    await this.loadScript();
    const factory = (globalThis as typeof globalThis & { Stripe?: StripeFactory }).Stripe;
    if (!factory) {
      throw new Error('Stripe secure payment UI is unavailable.');
    }
    this.paymentElement?.unmount();
    this.client = factory(publishableKey);
    this.elements = this.client.elements({ clientSecret });
    this.paymentElement = this.elements.create('payment');
    this.paymentElement.mount(selector);
    this.returnUrl = returnUrl;
  }

  async confirm(): Promise<StripeConfirmationResult> {
    if (!this.client || !this.elements || !this.returnUrl) {
      throw new Error('Stripe secure payment UI is not ready.');
    }
    return this.client.confirmPayment({
      elements: this.elements,
      confirmParams: { return_url: this.returnUrl },
      redirect: 'if_required',
    });
  }

  unmount(): void {
    this.paymentElement?.unmount();
    this.paymentElement = undefined;
    this.elements = undefined;
    this.client = undefined;
    this.returnUrl = undefined;
  }

  private loadScript(): Promise<void> {
    if (this.script) {
      return this.script;
    }
    this.script = new Promise<void>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>('script[data-msb-stripe-js]');
      if (existing) {
        if ((globalThis as typeof globalThis & { Stripe?: StripeFactory }).Stripe) {
          resolve();
        } else {
          existing.addEventListener('load', () => resolve(), { once: true });
          existing.addEventListener('error', () => reject(new Error('Stripe.js failed to load.')),
            { once: true });
        }
        return;
      }
      const script = document.createElement('script');
      script.src = 'https://js.stripe.com/v3/';
      script.async = true;
      script.dataset['msbStripeJs'] = 'true';
      script.addEventListener('load', () => resolve(), { once: true });
      script.addEventListener('error', () => reject(new Error('Stripe.js failed to load.')),
        { once: true });
      document.head.appendChild(script);
    });
    return this.script;
  }
}
