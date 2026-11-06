import {Composition} from 'remotion';
import {RoamttyDemo, demoDuration} from './RoamttyDemo';
import {SiteDemo, siteDuration} from './SiteDemo';

// 1920x1080 at 30 fps: the format Google Play and YouTube expect for a landscape demo.
export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="PlayReview"
        component={RoamttyDemo}
        durationInFrames={demoDuration(30)}
        fps={30}
        width={1920}
        height={1080}
        defaultProps={{variant: 'review' as const, lang: 'en' as const}}
      />
      <Composition
        id="SiteEn"
        component={SiteDemo}
        durationInFrames={siteDuration('en', 30)}
        fps={30}
        width={1920}
        height={1080}
        defaultProps={{lang: 'en' as const}}
      />
      <Composition
        id="SiteZh"
        component={SiteDemo}
        durationInFrames={siteDuration('zh', 30)}
        fps={30}
        width={1920}
        height={1080}
        defaultProps={{lang: 'zh' as const}}
      />
    </>
  );
};
