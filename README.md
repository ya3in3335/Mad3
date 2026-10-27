# Appnova — إعلان موشن ڨرافيك (23 ثانية، 1080×1080)

فيديو إعلاني لشركة **Appnova** لتطوير تطبيقات الويب والأندرويد: شعار متحرك، كتابة حركية، مؤثرات صوتية وموسيقى مولّدة، وتعليق صوتي أمريكي عميق عبر **edge-tts**.

## المشاهد

| الوقت | المشهد | التعليق الصوتي |
|---|---|---|
| 0.0–3.2 | YOUR IDEA / DESERVES MORE THAN A DREAM | Your idea... deserves more than a dream. |
| 3.2–7.0 | رسم نافذة متصفح + هاتف أندرويد، BUILT TO PERFORM | Web apps. Android apps. Built to perform. |
| 7.0–10.0 | FAST / SECURE / POWERFUL بضربات قوية | Fast. Secure. Powerful. |
| 10.0–13.6 | كود يُكتب + عدّاد 1,000,000+ مع كرة شبكة | From the first line of code... to millions of users. |
| 13.6–19.0 | اندفاع ضوئي ← انفجار ← ظهور الشعار + WE BUILD WHAT'S NEXT | Appnova. / We build what's next. |
| 19.0–23.0 | بطاقة النهاية: START YOUR PROJECT TODAY | Start your project today. |

كل التوقيتات في ملف واحد: `timeline.py` (الفيديو والصوت يقرآن منه).

## التشغيل على جهازك

المتطلبات: Python 3.10+ و ffmpeg في الـ PATH.

```bash
pip install numpy scipy pillow edge-tts

python render.py          # يصنع build/appnova_video_nosound.mp4
python sound_design.py    # يصنع build/music_sfx.wav (موسيقى + مؤثرات)
python make_voice.py      # يولّد الصوت بـ edge-tts ويدمج كل شيء
```

النتيجة النهائية: `build/appnova_ad_final.mp4`

## التحكم في الصوت

الصوت الافتراضي: `en-US-ChristopherNeural` مع خفض الطبقة (`-14Hz`) وإبطاء بسيط (`-8%`)، ثم في ffmpeg: خفض إضافي للطبقة، تقوية الترددات المنخفضة، خشونة خفيفة (soft clip)، ضغط، وصدى قصير بأسلوب الإعلانات. الموسيقى تنخفض تلقائيًا تحت الصوت (ducking).

```bash
python make_voice.py --voice en-US-GuyNeural          # صوت أكثر حماسًا
python make_voice.py --pitch -18Hz --deepen 0.92      # أعمق وأخشن
python make_voice.py --rate -2%                       # إذا تداخلت جملة مع التالية
python make_voice.py --skip-tts                       # إعادة الدمج بدون توليد الصوت من جديد
```

السكريبت يطبع مدة كل جملة وينبّهك إذا تجاوزت جملة وقت الجملة التالية.

## تعديل النصوص

- نص التعليق الصوتي وتوقيته: قائمة `VO` في `timeline.py`
- نصوص الشاشة: دوال المشاهد في `render.py` (`sc_idea`, `sc_devices`, `sc_punch`, `sc_scale`, `sc_logo`, `sc_cta`)
- الشعار: `assets/logo.png` (الرمز والكتابة يُقصّان منه تلقائيًا)
