const year = document.getElementById('year');
if (year) year.textContent = new Date().getFullYear();

const menuButton = document.querySelector('.menu-toggle');
const mobileMenu = document.getElementById('mobile-menu');

function closeMenu() {
  if (!menuButton || !mobileMenu) return;
  menuButton.setAttribute('aria-expanded', 'false');
  menuButton.setAttribute('aria-label', 'Открыть меню');
  mobileMenu.hidden = true;
  document.body.classList.remove('menu-open');
}

if (menuButton && mobileMenu) {
  menuButton.addEventListener('click', () => {
    const willOpen = menuButton.getAttribute('aria-expanded') !== 'true';
    menuButton.setAttribute('aria-expanded', String(willOpen));
    menuButton.setAttribute('aria-label', willOpen ? 'Закрыть меню' : 'Открыть меню');
    mobileMenu.hidden = !willOpen;
    document.body.classList.toggle('menu-open', willOpen);
  });

  mobileMenu.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', closeMenu);
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeMenu();
  });

  window.addEventListener('resize', () => {
    if (window.innerWidth > 900) closeMenu();
  });
}

/* Screenshot gallery:
   desktop/tablet — arrow buttons;
   phone — native finger swipe. */
const gallery = document.querySelector('.gallery');
const prevButton = document.querySelector('.gallery-arrow-prev');
const nextButton = document.querySelector('.gallery-arrow-next');

if (gallery && prevButton && nextButton) {
  const getStep = () => {
    const firstSlide = gallery.querySelector('figure');
    if (!firstSlide) return gallery.clientWidth * 0.8;

    const styles = window.getComputedStyle(gallery);
    const gap = parseFloat(styles.columnGap || styles.gap || 0) || 0;
    return firstSlide.getBoundingClientRect().width + gap;
  };

  const updateButtons = () => {
    const maxScroll = Math.max(0, gallery.scrollWidth - gallery.clientWidth);
    const edgeTolerance = 8;
    const atStart = gallery.scrollLeft <= edgeTolerance;
    const atEnd = gallery.scrollLeft >= maxScroll - edgeTolerance;

    prevButton.classList.toggle('is-hidden', atStart);
    nextButton.classList.toggle('is-hidden', atEnd);

    prevButton.disabled = atStart;
    nextButton.disabled = atEnd;
  };

  prevButton.addEventListener('click', () => {
    gallery.scrollBy({ left: -getStep(), behavior: 'smooth' });
  });

  nextButton.addEventListener('click', () => {
    gallery.scrollBy({ left: getStep(), behavior: 'smooth' });
  });

  gallery.addEventListener('scroll', updateButtons, { passive: true });
  window.addEventListener('resize', updateButtons);

  updateButtons();
  requestAnimationFrame(updateButtons);
}


// 2026-09-11 — видео подгружается только по клику, чтобы не тянуть
// сторонние скрипты и не замедлять первую загрузку страницы.
const videoFacade = document.querySelector('.video-facade');

if (videoFacade) {
  videoFacade.addEventListener('click', () => {
    const src = videoFacade.dataset.videoSrc;
    if (!src) return;

    const iframe = document.createElement('iframe');
    iframe.src = src.includes('?') ? `${src}&autoplay=1` : `${src}?autoplay=1`;
    iframe.title = 'Видео о приложении «Белый список?»';
    iframe.allow = 'autoplay; encrypted-media; fullscreen; picture-in-picture; screen-wake-lock;';
    iframe.allowFullscreen = true;
    iframe.setAttribute('frameborder', '0');

    videoFacade.replaceWith(iframe);
  });
}
