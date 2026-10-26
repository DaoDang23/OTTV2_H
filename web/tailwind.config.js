/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  safelist: [
    { pattern: /(bg|text|border)-(emerald|blue|rose|indigo|amber|zinc|pink|teal|slate)-(100|200|300|400|500|600|700|800|900)/ },
    { pattern: /bg-(emerald|blue|rose|indigo|amber|zinc|pink|teal|slate)-100\/(10|20|50|80)/ },
  ],
  theme: {
    extend: {
      colors: {
        zinc: {
          750: '#26262b',
          850: '#16161a',
        },
      },
    },
  },
  plugins: [],
};
