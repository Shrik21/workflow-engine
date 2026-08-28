(() => {
  const weekLabels = ["W1", "W2", "W3", "W4", "W5", "W6", "W7", "W8"];
  const completed = [120, 148, 162, 175, 190, 210, 238, 256];
  const recovered = [18, 22, 16, 25, 20, 28, 24, 30];

  function drawChart() {
    const bars = document.getElementById("bars");
    const labels = document.querySelector(".axis-labels");
    if (!bars || !labels) return;

    const max = Math.max(...completed.map((v, i) => v + recovered[i]));
    const chartTop = 40;
    const chartBottom = 190;
    const chartHeight = chartBottom - chartTop;
    const startX = 56;
    const gap = 58;
    const barWidth = 28;

    bars.innerHTML = "";
    labels.innerHTML = "";

    completed.forEach((value, index) => {
      const total = value + recovered[index];
      const h = (total / max) * chartHeight;
      const x = startX + index * gap;
      const y = chartBottom - h;
      const recoveredH = (recovered[index] / max) * chartHeight;
      const completedH = h - recoveredH;

      const completedBar = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      completedBar.setAttribute("x", String(x));
      completedBar.setAttribute("y", String(chartBottom));
      completedBar.setAttribute("width", String(barWidth));
      completedBar.setAttribute("height", "0");
      completedBar.setAttribute("fill", "url(#barFill)");
      completedBar.setAttribute("rx", "4");
      bars.appendChild(completedBar);

      const recoveredBar = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      recoveredBar.setAttribute("x", String(x));
      recoveredBar.setAttribute("y", String(chartBottom));
      recoveredBar.setAttribute("width", String(barWidth));
      recoveredBar.setAttribute("height", "0");
      recoveredBar.setAttribute("fill", "#0f2740");
      recoveredBar.setAttribute("opacity", "0.85");
      recoveredBar.setAttribute("rx", "4");
      bars.appendChild(recoveredBar);

      const delay = 80 + index * 55;
      window.setTimeout(() => {
        completedBar.setAttribute("y", String(y));
        completedBar.setAttribute("height", String(completedH));
        recoveredBar.setAttribute("y", String(y + completedH));
        recoveredBar.setAttribute("height", String(recoveredH));
      }, delay);

      const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
      label.setAttribute("x", String(x + barWidth / 2));
      label.setAttribute("y", "208");
      label.setAttribute("text-anchor", "middle");
      label.textContent = weekLabels[index];
      labels.appendChild(label);
    });
  }

  function animateCounts(root = document) {
    const nodes = root.querySelectorAll("[data-count]");
    nodes.forEach((node) => {
      const target = Number(node.getAttribute("data-count") || "0");
      const duration = 1100;
      const start = performance.now();

      const tick = (now) => {
        const progress = Math.min(1, (now - start) / duration);
        const eased = 1 - Math.pow(1 - progress, 3);
        node.textContent = String(Math.round(target * eased));
        if (progress < 1) requestAnimationFrame(tick);
      };

      requestAnimationFrame(tick);
    });
  }

  function observeReveals() {
    const items = document.querySelectorAll("[data-reveal]");
    if (!("IntersectionObserver" in window)) {
      items.forEach((el) => el.classList.add("is-visible"));
      drawChart();
      animateCounts();
      return;
    }

    let overviewAnimated = false;
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-visible");
          if (entry.target.id === "overview" && !overviewAnimated) {
            overviewAnimated = true;
            drawChart();
            animateCounts(entry.target);
          }
          observer.unobserve(entry.target);
        });
      },
      { threshold: 0.16 }
    );

    items.forEach((el) => observer.observe(el));
  }

  document.querySelectorAll('a[href^="#"]').forEach((link) => {
    link.addEventListener("click", (event) => {
      const id = link.getAttribute("href");
      if (!id || id === "#") return;
      const target = document.querySelector(id);
      if (!target) return;
      event.preventDefault();
      target.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  });

  observeReveals();
})();
