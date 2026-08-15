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

/* Screenshot gallery: swipe on touch devices, dot navigation on larger screens */
const gallery = document.querySelector('.gallery');

if (gallery) {
  const slides = Array.from(gallery.querySelectorAll('figure'));

  if (slides.length > 1) {
    const dots = document.createElement('div');
    dots.className = 'gallery-dots';
    dots.setAttribute('aria-label', 'Навигация по скриншотам');

    const buttons = slides.map((slide, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'gallery-dot';
      button.setAttribute('aria-label', `Показать скриншот ${index + 1}`);
      button.setAttribute('aria-current', index === 0 ? 'true' : 'false');

      if (index === 0) button.classList.add('is-active');

      button.addEventListener('click', () => {
        const targetLeft = slide.offsetLeft - gallery.offsetLeft;
        gallery.scrollTo({ left: targetLeft, behavior: 'smooth' });
      });

      dots.appendChild(button);
      return button;
    });

    gallery.insertAdjacentElement('afterend', dots);

    let ticking = false;

    const updateActiveDot = () => {
      const currentLeft = gallery.scrollLeft;
      let activeIndex = 0;
      let smallestDistance = Infinity;

      slides.forEach((slide, index) => {
        const slideLeft = slide.offsetLeft - gallery.offsetLeft;
        const distance = Math.abs(slideLeft - currentLeft);

        if (distance < smallestDistance) {
          smallestDistance = distance;
          activeIndex = index;
        }
      });

      buttons.forEach((button, index) => {
        const active = index === activeIndex;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-current', active ? 'true' : 'false');
      });

      ticking = false;
    };

    gallery.addEventListener('scroll', () => {
      if (!ticking) {
        window.requestAnimationFrame(updateActiveDot);
        ticking = true;
      }
    }, { passive: true });

    window.addEventListener('resize', updateActiveDot);
  }
}
