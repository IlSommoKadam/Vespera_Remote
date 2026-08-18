package com.vaonis.vesperahelper.crypto;

import java.security.SecureRandom;


/* loaded from: classes2.dex */
public final class TweetNacl {
    public static final long[] D;
    public static final long[] D2;
    public static final long[] I;
    public static final long[] K;
    public static final long[] L;
    public static final long[] X;
    public static final long[] Y;
    public static final long[] _121665;
    public static final long[] gf0;
    public static final long[] gf1;
    public static final byte[] iv;
    public static final SecureRandom jrandom;
    public static final byte[] sigma;
    public static final byte[] _0 = new byte[16];
    public static final byte[] _9 = new byte[32];

    public static final class Hash {
        public static byte[] sha512(byte[] bArr) {
            if (bArr == null || bArr.length <= 0) {
                return null;
            }
            byte[] bArr2 = new byte[64];
            TweetNacl.crypto_hash(bArr2, bArr);
            return bArr2;
        }
    }

    public static final class Signature {
        public byte[] mySecretKey;
        public byte[] theirPublicKey;

        public Signature(byte[] bArr, byte[] bArr2) {
            this.theirPublicKey = bArr;
            this.mySecretKey = bArr2;
        }

        public byte[] sign(byte[] bArr) {
            byte[] bArr2 = new byte[bArr.length + 64];
            TweetNacl.crypto_sign(bArr2, -1L, bArr, bArr.length, this.mySecretKey);
            return bArr2;
        }
    }

    static {
        byte[] bArr;
        long[] jArr;
        int i = 0;
        while (true) {
            byte[] bArr2 = _0;
            if (i >= bArr2.length) {
                break;
            }
            bArr2[i] = 0;
            i++;
        }
        int i2 = 0;
        while (true) {
            bArr = _9;
            if (i2 >= bArr.length) {
                break;
            }
            bArr[i2] = 0;
            i2++;
        }
        bArr[0] = 9;
        gf0 = new long[16];
        gf1 = new long[16];
        _121665 = new long[16];
        int i3 = 0;
        while (true) {
            long[] jArr2 = gf0;
            if (i3 >= jArr2.length) {
                break;
            }
            jArr2[i3] = 0;
            i3++;
        }
        int i4 = 0;
        while (true) {
            jArr = gf1;
            if (i4 >= jArr.length) {
                break;
            }
            jArr[i4] = 0;
            i4++;
        }
        jArr[0] = 1;
        int i5 = 0;
        while (true) {
            long[] jArr3 = _121665;
            if (i5 >= jArr3.length) {
                jArr3[0] = 56129;
                jArr3[1] = 1;
                D = new long[]{30883, 4953, 19914, 30187, 55467, 16705, 2637, 112, 59544, 30585, 16505, 36039, 65139, 11119, 27886, 20995};
                D2 = new long[]{61785, 9906, 39828, 60374, 45398, 33411, 5274, 224, 53552, 61171, 33010, 6542, 64743, 22239, 55772, 9222};
                X = new long[]{54554, 36645, 11616, 51542, 42930, 38181, 51040, 26924, 56412, 64982, 57905, 49316, 21502, 52590, 14035, 8553};
                Y = new long[]{26200, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214, 26214};
                I = new long[]{41136, 18958, 6951, 50414, 58488, 44335, 6150, 12099, 55207, 15867, 153, 11085, 57099, 20417, 9344, 11139};
                sigma = new byte[]{101, 120, 112, 97, 110, 100, 32, 51, 50, 45, 98, 121, 116, 101, 32, 107};
                K = new long[]{4794697086780616226L, 8158064640168781261L, -5349999486874862801L, -1606136188198331460L, 4131703408338449720L, 6480981068601479193L, -7908458776815382629L, -6116909921290321640L, -2880145864133508542L, 1334009975649890238L, 2608012711638119052L, 6128411473006802146L, 8268148722764581231L, -9160688886553864527L, -7215885187991268811L, -4495734319001033068L, -1973867731355612462L, -1171420211273849373L, 1135362057144423861L, 2597628984639134821L, 3308224258029322869L, 5365058923640841347L, 6679025012923562964L, 8573033837759648693L, -7476448914759557205L, -6327057829258317296L, -5763719355590565569L, -4658551843659510044L, -4116276920077217854L, -3051310485924567259L, 489312712824947311L, 1452737877330783856L, 2861767655752347644L, 3322285676063803686L, 5560940570517711597L, 5996557281743188959L, 7280758554555802590L, 8532644243296465576L, -9096487096722542874L, -7894198246740708037L, -6719396339535248540L, -6333637450476146687L, -4446306890439682159L, -4076793802049405392L, -3345356375505022440L, -2983346525034927856L, -860691631967231958L, 1182934255886127544L, 1847814050463011016L, 2177327727835720531L, 2830643537854262169L, 3796741975233480872L, 4115178125766777443L, 5681478168544905931L, 6601373596472566643L, 7507060721942968483L, 8399075790359081724L, 8693463985226723168L, -8878714635349349518L, -8302665154208450068L, -8016688836872298968L, -6606660893046293015L, -4685533653050689259L, -4147400797238176981L, -3880063495543823972L, -3348786107499101689L, -1523767162380948706L, -757361751448694408L, 500013540394364858L, 748580250866718886L, 1242879168328830382L, 1977374033974150939L, 2944078676154940804L, 3659926193048069267L, 4368137639120453308L, 4836135668995329356L, 5532061633213252278L, 6448918945643986474L, 6902733635092675308L, 7801388544844847127L};
                iv = new byte[]{106, 9, -26, 103, -13, -68, -55, 8, -69, 103, -82, -123, -124, -54, -89, 59, 60, 110, -13, 114, -2, -108, -8, 43, -91, 79, -11, 58, 95, 29, 54, -15, 81, 14, 82, Byte.MAX_VALUE, -83, -26, -126, -47, -101, 5, 104, -116, 43, 62, 108, 31, 31, -125, -39, -85, -5, 65, -67, 107, 91, -32, -51, 25, 19, 126, 33, 121};
                L = new long[]{237, 211, 245, 92, 26, 99, 18, 88, 214, 156, 247, 162, 222, 249, 222, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16};
                jrandom = new SecureRandom();
                return;
            }
            jArr3[i5] = 0;
            i5++;
        }
    }

    public static void A(long[] jArr, int i, int i2, long[] jArr2, int i3, int i4, long[] jArr3, int i5, int i6) {
        for (int i7 = 0; i7 < 16; i7++) {
            jArr[i7 + i] = jArr2[i7 + i3] + jArr3[i7 + i5];
        }
    }

    public static long Ch(long j, long j2, long j3) {
        return ((~j) & j3) ^ (j2 & j);
    }

    public static void M(long[] jArr, int i, int i2, long[] jArr2, int i3, int i4, long[] jArr3, int i5, int i6) {
        long[] jArr4 = new long[31];
        for (int i7 = 0; i7 < 31; i7++) {
            jArr4[i7] = 0;
        }
        for (int i8 = 0; i8 < 16; i8++) {
            for (int i9 = 0; i9 < 16; i9++) {
                int i10 = i8 + i9;
                jArr4[i10] = jArr4[i10] + (jArr2[i8 + i3] * jArr3[i9 + i5]);
            }
        }
        for (int i11 = 0; i11 < 15; i11++) {
            jArr4[i11] = jArr4[i11] + (jArr4[i11 + 16] * 38);
        }
        for (int i12 = 0; i12 < 16; i12++) {
            jArr[i12 + i] = jArr4[i12];
        }
        car25519(jArr, i, i2);
        car25519(jArr, i, i2);
    }

    public static long Maj(long j, long j2, long j3) {
        return ((j & j3) ^ (j & j2)) ^ (j2 & j3);
    }

    public static long R(long j, int i) {
        return (j << (64 - i)) | (j >>> i);
    }

    public static void S(long[] jArr, int i, int i2, long[] jArr2, int i3, int i4) {
        M(jArr, i, i2, jArr2, i3, i4, jArr2, i3, i4);
    }

    public static long Sigma0(long j) {
        return R(j, 39) ^ (R(j, 28) ^ R(j, 34));
    }

    public static long Sigma1(long j) {
        return R(j, 41) ^ (R(j, 14) ^ R(j, 18));
    }

    public static void Z(long[] jArr, int i, int i2, long[] jArr2, int i3, int i4, long[] jArr3, int i5, int i6) {
        for (int i7 = 0; i7 < 16; i7++) {
            jArr[i7 + i] = jArr2[i7 + i3] - jArr3[i7 + i5];
        }
    }

    public static void add(long[][] jArr, long[][] jArr2) {
        long[] jArr3 = new long[16];
        long[] jArr4 = new long[16];
        long[] jArr5 = new long[16];
        long[] jArr6 = new long[16];
        long[] jArr7 = new long[16];
        long[] jArr8 = new long[16];
        long[] jArr9 = new long[16];
        long[] jArr10 = new long[16];
        long[] jArr11 = new long[16];
        long[] jArr12 = jArr[0];
        long[] jArr13 = jArr[1];
        long[] jArr14 = jArr[2];
        long[] jArr15 = jArr[3];
        long[] jArr16 = jArr2[0];
        long[] jArr17 = jArr2[1];
        long[] jArr18 = jArr2[2];
        long[] jArr19 = jArr2[3];
        Z(jArr3, 0, 16, jArr13, 0, jArr13.length, jArr12, 0, jArr12.length);
        Z(jArr7, 0, 16, jArr17, 0, jArr17.length, jArr16, 0, jArr16.length);
        M(jArr3, 0, 16, jArr3, 0, 16, jArr7, 0, 16);
        A(jArr4, 0, 16, jArr12, 0, jArr12.length, jArr13, 0, jArr13.length);
        A(jArr7, 0, 16, jArr16, 0, jArr16.length, jArr17, 0, jArr17.length);
        M(jArr4, 0, 16, jArr4, 0, 16, jArr7, 0, 16);
        M(jArr5, 0, 16, jArr15, 0, jArr15.length, jArr19, 0, jArr19.length);
        long[] jArr20 = D2;
        M(jArr5, 0, 16, jArr5, 0, 16, jArr20, 0, jArr20.length);
        M(jArr6, 0, 16, jArr14, 0, jArr14.length, jArr18, 0, jArr18.length);
        A(jArr6, 0, 16, jArr6, 0, 16, jArr6, 0, 16);
        Z(jArr8, 0, 16, jArr4, 0, 16, jArr3, 0, 16);
        Z(jArr9, 0, 16, jArr6, 0, 16, jArr5, 0, 16);
        A(jArr10, 0, 16, jArr6, 0, 16, jArr5, 0, 16);
        A(jArr11, 0, 16, jArr4, 0, 16, jArr3, 0, 16);
        M(jArr12, 0, jArr12.length, jArr8, 0, 16, jArr9, 0, 16);
        M(jArr13, 0, jArr13.length, jArr11, 0, 16, jArr10, 0, 16);
        M(jArr14, 0, jArr14.length, jArr10, 0, 16, jArr9, 0, 16);
        M(jArr15, 0, jArr15.length, jArr8, 0, 16, jArr11, 0, 16);
    }

    public static void car25519(long[] jArr, int i, int i2) {
        int i3 = 0;
        while (i3 < 16) {
            int i4 = i3 + i;
            long j = jArr[i4] + 65536;
            jArr[i4] = j;
            long j2 = j >> 16;
            int i5 = i3 + 1;
            int i6 = 1;
            int i7 = ((i3 < 15 ? 1 : 0) * i5) + i;
            long j3 = jArr[i7];
            long j4 = j2 - 1;
            long j5 = 37 * j4;
            if (i3 != 15) {
                i6 = 0;
            }
            jArr[i7] = j3 + j4 + (j5 * i6);
            jArr[i4] = jArr[i4] - (j2 << 16);
            i3 = i5;
        }
    }

    public static int crypto_hash(byte[] bArr, byte[] bArr2) {
        return crypto_hash(bArr, bArr2, bArr2 != null ? bArr2.length : 0);
    }

    public static int crypto_hash(byte[] bArr, byte[] bArr2, int i) {
        return crypto_hash(bArr, bArr2, 0, bArr2.length, i);
    }

    public static int crypto_hash(byte[] bArr, byte[] bArr2, int i, int i2, int i3) {
        byte[] bArr3 = new byte[64];
        byte[] bArr4 = new byte[256];
        long j = i3;
        for (int i4 = 0; i4 < 64; i4++) {
            bArr3[i4] = iv[i4];
        }
        crypto_hashblocks(bArr3, bArr2, i, i2, i3);
        int i5 = i3 & 127;
        for (int i6 = 0; i6 < 256; i6++) {
            bArr4[i6] = 0;
        }
        for (int i7 = 0; i7 < i5; i7++) {
            bArr4[i7] = bArr2[i7 + i];
        }
        bArr4[i5] = Byte.MIN_VALUE;
        int i8 = (i5 < 112 ? 1 : 0) * 128;
        int i9 = 256 - i8;
        bArr4[247 - i8] = (byte) (j >>> 61);
        int i10 = 248 - i8;
        ts64(bArr4, i10, 256 - i10, j << 3);
        crypto_hashblocks(bArr3, bArr4, 0, 256, i9);
        for (int i11 = 0; i11 < 64; i11++) {
            bArr[i11] = bArr3[i11];
        }
        return 0;
    }

    public static int crypto_hashblocks(byte[] bArr, byte[] bArr2, int i, int i2, int i3) {
        long[] jArr = new long[8];
        long[] jArr2 = new long[8];
        long[] jArr3 = new long[8];
        long[] jArr4 = new long[16];
        for (int i4 = 0; i4 < 8; i4++) {
            int i5 = i4 * 8;
            long dl64 = dl64(bArr, i5, bArr.length - i5);
            jArr3[i4] = dl64;
            jArr[i4] = dl64;
        }
        int i6 = i;
        int i7 = i3;
        while (i7 >= 128) {
            for (int i8 = 0; i8 < 16; i8++) {
                int i9 = i8 * 8;
                jArr4[i8] = dl64(bArr2, i9 + i6, i2 - i9);
            }
            for (int i10 = 0; i10 < 80; i10++) {
                for (int i11 = 0; i11 < 8; i11++) {
                    jArr2[i11] = jArr3[i11];
                }
                int i12 = i10 % 16;
                long Sigma1 = jArr3[7] + Sigma1(jArr3[4]) + Ch(jArr3[4], jArr3[5], jArr3[6]) + K[i10] + jArr4[i12];
                jArr2[7] = Sigma1 + Sigma0(jArr3[0]) + Maj(jArr3[0], jArr3[1], jArr3[2]);
                jArr2[3] = jArr2[3] + Sigma1;
                int i13 = 0;
                while (i13 < 8) {
                    int i14 = i13 + 1;
                    jArr3[i14 % 8] = jArr2[i13];
                    i13 = i14;
                }
                if (i12 == 15) {
                    int i15 = 0;
                    while (i15 < 16) {
                        int i16 = i15 + 1;
                        jArr4[i15] = jArr4[i15] + jArr4[(i15 + 9) % 16] + sigma0(jArr4[i16 % 16]) + sigma1(jArr4[(i15 + 14) % 16]);
                        i15 = i16;
                    }
                }
            }
            for (int i17 = 0; i17 < 8; i17++) {
                long j = jArr3[i17] + jArr[i17];
                jArr3[i17] = j;
                jArr[i17] = j;
            }
            i6 += 128;
            i7 -= 128;
        }
        for (int i18 = 0; i18 < 8; i18++) {
            int i19 = i18 * 8;
            ts64(bArr, i19, bArr.length - i19, jArr[i18]);
        }
        return i7;
    }

    public static int crypto_sign(byte[] bArr, long j, byte[] bArr2, int i, byte[] bArr3) {
        byte[] bArr4 = new byte[64];
        byte[] bArr5 = new byte[64];
        byte[] bArr6 = new byte[64];
        long[] jArr = new long[64];
        long[][] jArr2 = {new long[16], new long[16], new long[16], new long[16]};
        crypto_hash(bArr4, bArr3, 0, bArr3.length, 32);
        bArr4[0] = (byte) (bArr4[0] & 248);
        byte b = (byte) (bArr4[31] & Byte.MAX_VALUE);
        bArr4[31] = b;
        bArr4[31] = (byte) (b | 64);
        for (int i2 = 0; i2 < i; i2++) {
            bArr[i2 + 64] = bArr2[i2];
        }
        for (int i3 = 0; i3 < 32; i3++) {
            int i4 = i3 + 32;
            bArr[i4] = bArr4[i4];
        }
        crypto_hash(bArr6, bArr, 32, bArr.length - 32, i + 32);
        reduce(bArr6);
        scalarbase(jArr2, bArr6, 0, 64);
        pack(bArr, jArr2);
        for (int i5 = 0; i5 < 32; i5++) {
            int i6 = i5 + 32;
            bArr[i6] = bArr3[i6];
        }
        crypto_hash(bArr5, bArr, 0, bArr.length, i + 64);
        reduce(bArr5);
        for (int i7 = 0; i7 < 64; i7++) {
            jArr[i7] = 0;
        }
        for (int i8 = 0; i8 < 32; i8++) {
            jArr[i8] = bArr6[i8] & 255;
        }
        for (int i9 = 0; i9 < 32; i9++) {
            for (int i10 = 0; i10 < 32; i10++) {
                int i11 = i9 + i10;
                jArr[i11] = jArr[i11] + ((bArr5[i9] & 255) * (bArr4[i10] & 255));
            }
        }
        modL(bArr, 32, bArr.length - 32, jArr);
        return 0;
    }

    public static void cswap(long[][] jArr, long[][] jArr2, byte b) {
        for (int i = 0; i < 4; i++) {
            long[] jArr3 = jArr[i];
            int length = jArr3.length;
            long[] jArr4 = jArr2[i];
            sel25519(jArr3, 0, length, jArr4, 0, jArr4.length, b);
        }
    }

    public static long dl64(byte[] bArr, int i, int i2) {
        long j = 0;
        for (int i3 = 0; i3 < 8; i3++) {
            j = (j << 8) | (bArr[i3 + i] & 255);
        }
        return j;
    }

    public static void inv25519(long[] jArr, int i, int i2, long[] jArr2, int i3, int i4) {
        long[] jArr3 = new long[16];
        for (int i5 = 0; i5 < 16; i5++) {
            jArr3[i5] = jArr2[i5 + i3];
        }
        for (int i6 = 253; i6 >= 0; i6--) {
            S(jArr3, 0, 16, jArr3, 0, 16);
            if (i6 != 2 && i6 != 4) {
                M(jArr3, 0, 16, jArr3, 0, 16, jArr2, i3, i4);
            }
        }
        for (int i7 = 0; i7 < 16; i7++) {
            jArr[i7 + i] = jArr3[i7];
        }
    }

    public static void modL(byte[] bArr, int i, int i2, long[] jArr) {
        long j;
        int i3 = 63;
        while (true) {
            j = 0;
            if (i3 < 32) {
                break;
            }
            int i4 = i3 - 32;
            long j2 = 0;
            int i5 = i4;
            while (i5 < i3 - 12) {
                long j3 = jArr[i5] + (j2 - ((jArr[i3] * 16) * L[i5 - i4]));
                jArr[i5] = j3;
                j2 = (128 + j3) >> 8;
                jArr[i5] = j3 - (j2 << 8);
                i5++;
            }
            jArr[i5] = jArr[i5] + j2;
            jArr[i3] = 0;
            i3--;
        }
        int i6 = 0;
        for (int i7 = 0; i7 < 32; i7++) {
            long j4 = jArr[i7] + (j - ((jArr[31] >> 4) * L[i7]));
            jArr[i7] = j4;
            j = j4 >> 8;
            jArr[i7] = 255 & j4;
        }
        for (int i8 = 0; i8 < 32; i8++) {
            jArr[i8] = jArr[i8] - (L[i8] * j);
        }
        while (i6 < 32) {
            int i9 = i6 + 1;
            jArr[i9] = jArr[i9] + (jArr[i6] >> 8);
            bArr[i6 + i] = (byte) (jArr[i6] & 255);
            i6 = i9;
        }
    }

    public static void pack(byte[] bArr, long[][] jArr) {
        long[] jArr2 = new long[16];
        long[] jArr3 = new long[16];
        long[] jArr4 = new long[16];
        long[] jArr5 = jArr[2];
        inv25519(jArr4, 0, 16, jArr5, 0, jArr5.length);
        long[] jArr6 = jArr[0];
        M(jArr2, 0, 16, jArr6, 0, jArr6.length, jArr4, 0, 16);
        long[] jArr7 = jArr[1];
        M(jArr3, 0, 16, jArr7, 0, jArr7.length, jArr4, 0, 16);
        pack25519(bArr, jArr3, 0, 16);
        bArr[31] = (byte) (bArr[31] ^ (par25519(jArr2) << 7));
    }

    public static void pack25519(byte[] bArr, long[] jArr, int i, int i2) {
        long[] jArr2 = new long[16];
        long[] jArr3 = new long[16];
        for (int i3 = 0; i3 < 16; i3++) {
            jArr3[i3] = jArr[i3 + i];
        }
        car25519(jArr3, 0, 16);
        car25519(jArr3, 0, 16);
        car25519(jArr3, 0, 16);
        for (int i4 = 0; i4 < 2; i4++) {
            jArr2[0] = jArr3[0] - 65517;
            for (int i5 = 1; i5 < 15; i5++) {
                int i6 = i5 - 1;
                jArr2[i5] = (jArr3[i5] - 65535L) - ((jArr2[i6] >> 16) & 1);
                jArr2[i6] = jArr2[i6] & 65535L;
            }
            long j = jArr3[15] - 32767;
            long j2 = jArr2[14];
            long j3 = j - ((j2 >> 16) & 1);
            jArr2[15] = j3;
            jArr2[14] = j2 & 65535L;
            sel25519(jArr3, 0, 16, jArr2, 0, 16, 1 - ((int) (1 & (j3 >> 16))));
        }
        for (int i7 = 0; i7 < 16; i7++) {
            int i8 = i7 * 2;
            long j4 = jArr3[i7];
            bArr[i8] = (byte) (255 & j4);
            bArr[i8 + 1] = (byte) (j4 >> 8);
        }
    }

    public static byte par25519(long[] jArr) {
        byte[] bArr = new byte[32];
        pack25519(bArr, jArr, 0, jArr.length);
        return (byte) (bArr[0] & 1);
    }

    public static void reduce(byte[] bArr) {
        long[] jArr = new long[64];
        for (int i = 0; i < 64; i++) {
            jArr[i] = bArr[i] & 255;
        }
        for (int i2 = 0; i2 < 64; i2++) {
            bArr[i2] = 0;
        }
        modL(bArr, 0, bArr.length, jArr);
    }

    public static void scalarbase(long[][] jArr, byte[] bArr, int i, int i2) {
        long[][] jArr2 = {new long[16], new long[16], new long[16], new long[16]};
        long[] jArr3 = jArr2[0];
        long[] jArr4 = X;
        set25519(jArr3, jArr4);
        long[] jArr5 = jArr2[1];
        long[] jArr6 = Y;
        set25519(jArr5, jArr6);
        set25519(jArr2[2], gf1);
        long[] jArr7 = jArr2[3];
        M(jArr7, 0, jArr7.length, jArr4, 0, jArr4.length, jArr6, 0, jArr6.length);
        scalarmult(jArr, jArr2, bArr, i, i2);
    }

    public static void scalarmult(long[][] jArr, long[][] jArr2, byte[] bArr, int i, int i2) {
        long[] jArr3 = jArr[0];
        long[] jArr4 = gf0;
        set25519(jArr3, jArr4);
        long[] jArr5 = jArr[1];
        long[] jArr6 = gf1;
        set25519(jArr5, jArr6);
        set25519(jArr[2], jArr6);
        set25519(jArr[3], jArr4);
        for (int i3 = 255; i3 >= 0; i3--) {
            byte b = (byte) ((bArr[(i3 / 8) + i] >> (i3 & 7)) & 1);
            cswap(jArr, jArr2, b);
            add(jArr2, jArr);
            add(jArr, jArr);
            cswap(jArr, jArr2, b);
        }
    }

    public static void sel25519(long[] jArr, int i, int i2, long[] jArr2, int i3, int i4, int i5) {
        long j = ~(i5 - 1);
        for (int i6 = 0; i6 < 16; i6++) {
            int i7 = i6 + i;
            long j2 = jArr[i7];
            int i8 = i6 + i3;
            long j3 = (jArr2[i8] ^ j2) & j;
            jArr[i7] = j2 ^ j3;
            jArr2[i8] = jArr2[i8] ^ j3;
        }
    }

    public static void set25519(long[] jArr, long[] jArr2) {
        for (int i = 0; i < 16; i++) {
            jArr[i] = jArr2[i];
        }
    }

    public static long sigma0(long j) {
        return (j >>> 7) ^ (R(j, 1) ^ R(j, 8));
    }

    public static long sigma1(long j) {
        return (j >>> 6) ^ (R(j, 19) ^ R(j, 61));
    }

    public static void ts64(byte[] bArr, int i, int i2, long j) {
        for (int i3 = 7; i3 >= 0; i3--) {
            bArr[i3 + i] = (byte) (255 & j);
            j >>>= 8;
        }
    }
}
