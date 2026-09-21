import { describe, expect, it } from 'vitest';
import { fingerprintOf, parseBankAlert } from '../src/sms.js';

const at = new Date('2026-09-20T13:12:00+05:30');

describe('parseBankAlert — real message shapes', () => {
  it('reads an HDFC UPI debit', () => {
    const parsed = parseBankAlert(
      'Sent Rs.486.00 from HDFC Bank A/C x4471 to SWIGGY on 20-09-26. UPI Ref 528113094412. Not you? Call 18002586161',
      { sender: 'VM-HDFCBK', receivedAt: at }
    );
    expect(parsed).not.toBeNull();
    expect(parsed!.direction).toBe('DEBIT');
    expect(parsed!.amountMinor).toBe(48_600);
    expect(parsed!.accountMask).toBe('4471');
    expect(parsed!.merchantRaw).toBe('SWIGGY');
    expect(parsed!.merchantNormalized).toBe('swiggy');
    expect(parsed!.referenceNumber).toBe('528113094412');
    expect(parsed!.bankId).toBe('hdfc');
    expect(parsed!.confidence).toBeGreaterThan(0.8);
  });

  it('reads an ICICI credit-card spend with a limit line', () => {
    const parsed = parseBankAlert(
      'INR 12,499.00 spent on ICICI Bank Card XX9082 on 19-Sep-26 at CROMA. Avl Lmt: INR 87,501.00',
      { sender: 'AD-ICICIB', receivedAt: at }
    );
    expect(parsed!.direction).toBe('DEBIT');
    expect(parsed!.amountMinor).toBe(1_249_900);
    expect(parsed!.accountMask).toBe('9082');
    expect(parsed!.accountKind).toBe('CREDIT_CARD');
    expect(parsed!.merchantRaw).toBe('CROMA');
    expect(parsed!.bankId).toBe('icici');
  });

  it('reads a UPI credit and keeps the payer VPA', () => {
    const parsed = parseBankAlert(
      'Credited Rs.643.00 to HDFC Bank A/C x4471 from ROHANKAMAT@okhdfcbank on 20-09-26. UPI Ref 528007712330',
      { sender: 'VM-HDFCBK', receivedAt: at }
    );
    expect(parsed!.direction).toBe('CREDIT');
    expect(parsed!.amountMinor).toBe(64_300);
    expect(parsed!.merchantRaw).toBe('ROHANKAMAT@okhdfcbank');
  });

  it('reads an SBI debit with a narration field', () => {
    const parsed = parseBankAlert(
      'Dear Customer, Rs.2575.00 debited from A/c XXXXX1234 on 19-09-26. Info: POS/SOCIAL OFFLINE BANDRA. Avl Bal Rs.41,225.50 -SBI',
      { sender: 'JD-SBIINB', receivedAt: at }
    );
    expect(parsed!.direction).toBe('DEBIT');
    expect(parsed!.amountMinor).toBe(257_500);
    expect(parsed!.accountMask).toBe('1234');
    expect(parsed!.merchantNormalized).toBe('social offline bandra');
    expect(parsed!.balanceMinor).toBe(4_122_550);
    expect(parsed!.bankId).toBe('sbi');
  });

  it('reads an Axis ATM withdrawal', () => {
    const parsed = parseBankAlert(
      'INR 5000 withdrawn from Axis Bank A/c XX7788 at ATM on 18-09-26. Avl Bal INR 12000.00',
      { sender: 'AX-AXISBK', receivedAt: at }
    );
    expect(parsed!.direction).toBe('DEBIT');
    expect(parsed!.amountMinor).toBe(500_000);
    expect(parsed!.accountKind).toBe('DEBIT_CARD');
  });

  it('handles the ₹ symbol and a merchant with punctuation', () => {
    const parsed = parseBankAlert(
      'You have paid ₹1,198.00 to ZUDIO - PHOENIX MALL from Kotak Bank A/c XX5566. Ref 8812771221',
      { sender: 'VK-KOTAKB', receivedAt: at }
    );
    expect(parsed!.amountMinor).toBe(119_800);
    expect(parsed!.merchantRaw).toBe('ZUDIO - PHOENIX MALL');
    expect(parsed!.bankId).toBe('kotak');
  });
});

describe('parseBankAlert — what it must refuse', () => {
  const rejected: Array<[string, string]> = [
    ['an OTP', '123456 is your OTP for a transaction of Rs.2000 on HDFC Bank Card xx4471. Do not share it with anyone.'],
    ['a future debit', 'Rs.1,299.00 will be debited from your A/c xx4471 on 25-09-26 towards ACT Fibernet.'],
    ['a collect request', 'PAYTM has requested Rs.500.00 from your A/c. Approve in your UPI app.'],
    ['a due-date reminder', 'Your ICICI Bank Credit Card bill of Rs.45,120.00 is due on 28-Sep-26. Min amount due Rs.2,300.'],
    ['a failed payment', 'Your payment of Rs.999.00 to NETFLIX has failed. Please retry.'],
    ['a balance enquiry', 'Available balance in your A/c XX4471 is Rs.41,225.50 as on 20-09-26.'],
    ['a promo', 'Pre-approved personal loan of Rs.5,00,000 for you! Apply now, T&C apply, click bit.ly/x'],
    ['an e-mandate notice', 'E-mandate registered for Rs.199.00 per month towards SPOTIFY on your Card xx9082.'],
    ['a reversal', 'Rs.486.00 debited on 20-09-26 has been reversed to your A/c xx4471.'],
    ['a short blank message', 'Hello there'],
    ['an empty string', '']
  ];

  for (const [label, body] of rejected) {
    it(`refuses ${label}`, () => {
      expect(parseBankAlert(body, { sender: 'VM-HDFCBK', receivedAt: at })).toBeNull();
    });
  }

  it('refuses a message with a verb but no amount', () => {
    expect(parseBankAlert('Your account has been debited today. Check the app.', { receivedAt: at })).toBeNull();
  });
});

describe('fingerprints', () => {
  it('are stable for the same alert', () => {
    const body = 'Sent Rs.486.00 from HDFC Bank A/C x4471 to SWIGGY on 20-09-26. UPI Ref 528113094412';
    const a = parseBankAlert(body, { receivedAt: at })!;
    const b = parseBankAlert(body, { receivedAt: at })!;
    expect(a.fingerprint).toBe(b.fingerprint);
  });

  it('differ for a different amount at the same merchant', () => {
    const a = parseBankAlert('Sent Rs.486.00 from A/C x4471 to SWIGGY on 20-09-26. Ref 111111111', { receivedAt: at })!;
    const b = parseBankAlert('Sent Rs.487.00 from A/C x4471 to SWIGGY on 20-09-26. Ref 222222222', { receivedAt: at })!;
    expect(a.fingerprint).not.toBe(b.fingerprint);
  });

  it('separate two same-amount debits on different days when there is no reference', () => {
    const base = { direction: 'DEBIT' as const, amountMinor: 48_600, accountMask: '4471', merchantNormalized: 'swiggy' };
    const day1 = fingerprintOf({ ...base, receivedAt: new Date('2026-09-20T12:00:00Z') });
    const day2 = fingerprintOf({ ...base, receivedAt: new Date('2026-09-21T12:00:00Z') });
    expect(day1).not.toBe(day2);
  });

  it('match on the reference number regardless of arrival time', () => {
    const base = { direction: 'DEBIT' as const, amountMinor: 48_600, referenceNumber: 'ABC123456' };
    expect(fingerprintOf({ ...base, receivedAt: new Date('2026-09-20T12:00:00Z') })).toBe(
      fingerprintOf({ ...base, receivedAt: new Date('2026-09-25T18:30:00Z') })
    );
  });
});
